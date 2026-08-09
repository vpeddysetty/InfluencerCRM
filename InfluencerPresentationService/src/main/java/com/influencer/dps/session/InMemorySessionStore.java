package com.influencer.dps.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.influencer.dps.config.DpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link SessionStore}: Caffeine, in this JVM.
 *
 * <p>Correct for a single instance and nothing more. With two DPS instances behind a load balancer,
 * instance B cannot see a session created by instance A, and the user is logged out whenever the
 * balancer moves them — the same class of failure the refresh-token map caused before it moved to
 * Postgres.
 *
 * <p>{@code @ConditionalOnMissingBean} makes the Redis swap a matter of supplying another bean; no
 * caller changes. The startup warning exists so the limitation is noticed before it bites in
 * production rather than after.
 */
public class InMemorySessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionStore.class);

    private final Cache<String, UiSession> sessions;
    private final Duration ttl;

    public InMemorySessionStore(DpsProperties properties) {
        this.ttl = Duration.ofMinutes(properties.getSessionTtlMinutes());
        this.sessions = Caffeine.newBuilder()
                // Sliding: activity extends the session. `expireAfterAccess` matches how a user
                // experiences a session — idleness ends it, use does not.
                .expireAfterAccess(ttl)
                .maximumSize(properties.getMaxSessions())
                .build();

        log.warn("Sessions are held in memory. Correct for a single instance only — a second "
                + "instance cannot see these sessions and users would be logged out when the load "
                + "balancer moves them. Provide a Redis-backed SessionStore bean before scaling out.");
    }

    @Override
    public void save(UiSession session) {
        sessions.put(session.sessionId(), session);
    }

    @Override
    public Optional<UiSession> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        UiSession session = sessions.getIfPresent(sessionId);
        if (session == null) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        if (session.isExpired(now)) {
            // Caffeine's own expiry and the session's recorded expiry can disagree by a moment;
            // the recorded one is authoritative.
            sessions.invalidate(sessionId);
            return Optional.empty();
        }

        UiSession touched = session.touch(now, ttl);
        sessions.put(sessionId, touched);
        return Optional.of(touched);
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessions.invalidate(sessionId);
        }
    }

    @Override
    public int deleteAllForUser(UUID userId) {
        if (userId == null) {
            return 0;
        }
        var doomed = sessions.asMap().entrySet().stream()
                .filter(entry -> userId.equals(entry.getValue().userId()))
                .map(java.util.Map.Entry::getKey)
                .toList();
        doomed.forEach(sessions::invalidate);
        return doomed.size();
    }

    @Override
    public long size() {
        return sessions.estimatedSize();
    }
}
