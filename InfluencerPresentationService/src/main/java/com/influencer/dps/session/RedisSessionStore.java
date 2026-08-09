package com.influencer.dps.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.dps.config.DpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sessions in Redis, so every DPS instance sees the same session.
 *
 * <p>This is what makes running more than one instance possible. With the in-memory store, instance
 * B cannot see a session created by instance A, so a user is logged out whenever the load balancer
 * moves them — the same failure the refresh-token map caused before it moved to Postgres.
 *
 * <h3>Two keys per session, on purpose</h3>
 * <pre>
 *   dps:session:{sessionId}   → the serialised session (TTL = session lifetime)
 *   dps:user:{userId}         → SET of that user's session ids (TTL refreshed alongside)
 * </pre>
 * The reverse index exists so "log this user out everywhere" is a set lookup rather than a scan.
 * {@code KEYS} and even {@code SCAN} over a production keyspace is the classic way to stall a Redis
 * instance, and revoking a compromised account is exactly when you cannot afford that.
 *
 * <h3>Expiry</h3>
 * Redis TTL does the work. Every read rewrites the key with a fresh TTL, giving sliding expiry that
 * matches how a user experiences a session: activity extends it, idleness ends it. The recorded
 * {@code expiresAt} is still checked, because a key can outlive its logical expiry by a moment.
 */
public class RedisSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);

    private static final String SESSION_PREFIX = "dps:session:";
    private static final String USER_PREFIX = "dps:user:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper, DpsProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(properties.getSessionTtlMinutes());
        log.info("Sessions are stored in Redis. Multiple DPS instances share them, so a load "
                + "balancer may move a user freely.");
    }

    @Override
    public void save(UiSession session) {
        try {
            redis.opsForValue().set(sessionKey(session.sessionId()), serialize(session), ttl);

            if (session.userId() != null) {
                String userKey = userKey(session.userId());
                redis.opsForSet().add(userKey, session.sessionId());
                // The index must not outlive the sessions it points at, or it accumulates dead ids
                // forever. Given a little slack so it never expires before its own members.
                redis.expire(userKey, ttl.plusMinutes(10));
            }
        } catch (Exception exception) {
            // A session that cannot be saved means the user is not logged in. Failing loudly is
            // right: silently continuing would produce an apparently successful login followed by
            // an immediate logout, which is far harder to diagnose.
            throw new IllegalStateException("Unable to persist the session to Redis", exception);
        }
    }

    @Override
    public Optional<UiSession> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(sessionKey(sessionId));
            if (raw == null) {
                return Optional.empty();
            }

            UiSession session = objectMapper.readValue(raw, UiSession.class);
            Instant now = Instant.now();
            if (session.isExpired(now)) {
                // Redis TTL and the recorded expiry can disagree by a moment; the recorded one wins.
                delete(sessionId);
                return Optional.empty();
            }

            // Sliding expiry: reading is evidence the user is active, and an active user should not
            // be logged out mid-task.
            UiSession touched = session.touch(now, ttl);
            redis.opsForValue().set(sessionKey(sessionId), serialize(touched), ttl);
            return Optional.of(touched);
        } catch (Exception exception) {
            // Treat an unreadable session as absent rather than propagating. A Redis blip should
            // present as "please log in again", not a 500 on every request.
            log.warn("Could not read session {} from Redis; treating it as absent", sessionId, exception);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            // Read the session first so its id can be removed from the user index. Skipping this
            // would leave the index growing with ids that no longer resolve.
            String raw = redis.opsForValue().get(sessionKey(sessionId));
            if (raw != null) {
                UiSession session = objectMapper.readValue(raw, UiSession.class);
                if (session.userId() != null) {
                    redis.opsForSet().remove(userKey(session.userId()), sessionId);
                }
            }
            redis.delete(sessionKey(sessionId));
        } catch (Exception exception) {
            // Best effort: the key expires on its own, and a failed logout must not throw.
            log.warn("Could not delete session {} from Redis", sessionId, exception);
        }
    }

    @Override
    public int deleteAllForUser(UUID userId) {
        if (userId == null) {
            return 0;
        }
        try {
            String userKey = userKey(userId);
            Set<String> sessionIds = redis.opsForSet().members(userKey);
            if (sessionIds == null || sessionIds.isEmpty()) {
                return 0;
            }
            sessionIds.forEach(id -> redis.delete(sessionKey(id)));
            redis.delete(userKey);
            return sessionIds.size();
        } catch (Exception exception) {
            // This is the "revoke a compromised account" path, so a failure must be visible rather
            // than reported as a successful revocation.
            log.error("Could not revoke sessions for user {}", userId, exception);
            throw new IllegalStateException("Unable to revoke sessions", exception);
        }
    }

    @Override
    public long size() {
        try {
            // Deliberately an estimate via the user index rather than KEYS over the whole keyspace:
            // this is operational visibility, not application logic, and is not worth stalling
            // Redis for.
            Set<String> keys = redis.keys(USER_PREFIX + "*");
            return keys == null ? 0 : keys.size();
        } catch (Exception exception) {
            return -1;
        }
    }

    private String serialize(UiSession session) throws Exception {
        return objectMapper.writeValueAsString(session);
    }

    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private String userKey(UUID userId) {
        return USER_PREFIX + userId;
    }
}
