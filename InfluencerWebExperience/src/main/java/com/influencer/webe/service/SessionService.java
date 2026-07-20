package com.influencer.webe.service;

import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;

    public SessionService(WebExperienceProperties properties) {
        this.ttl = Duration.ofMinutes(properties.getSessionTtlMinutes());
    }

    public SessionInfo createSession(UUID userId, String email, String provider) {
        String token = UUID.randomUUID().toString();
        SessionInfo session = new SessionInfo(token, userId, email, provider, Instant.now(), Instant.now().plus(ttl));
        sessions.put(token, session);
        return session;
    }

    public Optional<SessionInfo> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        SessionInfo session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void invalidate(String token) {
        sessions.remove(token);
    }

    public record SessionInfo(String token, UUID userId, String email, String provider, Instant issuedAt, Instant expiresAt) {
    }
}
