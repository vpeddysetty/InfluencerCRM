package com.influencer.webe.security;

import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds refresh tokens so that sessions remain revocable while access tokens stay stateless.
 *
 * <p>Access tokens are short-lived and verified by signature alone (no lookup). Revocation therefore
 * happens here: dropping the refresh token stops the session from being renewed, bounded by the
 * access-token TTL.
 *
 * <p>Storage note: this is an in-memory implementation with the persistence seam already in place.
 * It is <em>not</em> a multi-instance solution — Phase 1 moves the backing map to a Postgres table
 * (docs/ddd-roadmap.md), at which point only the four private map operations below change. Unlike
 * the SessionService it replaces, losing this map logs users out at the next refresh rather than
 * instantly, and no authorization decision depends on it.
 */
@Component
public class RefreshTokenStore {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, StoredRefreshToken> tokensByHash = new ConcurrentHashMap<>();
    private final Duration refreshTokenTtl;

    public RefreshTokenStore(WebExperienceProperties properties) {
        this.refreshTokenTtl = Duration.ofMinutes(properties.getRefreshTokenTtlMinutes());
    }

    /** Returns the raw token to hand to the client; only its hash is retained. */
    public String issue(UUID userId, String provider) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        tokensByHash.put(hash(token), new StoredRefreshToken(
                userId, provider, Instant.now().plus(refreshTokenTtl)));
        return token;
    }

    public Optional<StoredRefreshToken> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hash(token);
        StoredRefreshToken stored = tokensByHash.get(tokenHash);
        if (stored == null) {
            return Optional.empty();
        }
        if (stored.expiresAt().isBefore(Instant.now())) {
            tokensByHash.remove(tokenHash);
            return Optional.empty();
        }
        return Optional.of(stored);
    }

    /** Rotates on use: the presented token is consumed and a fresh one returned. */
    public Optional<RotatedToken> rotate(String presentedToken) {
        Optional<StoredRefreshToken> stored = resolve(presentedToken);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        tokensByHash.remove(hash(presentedToken));
        StoredRefreshToken previous = stored.get();
        String replacement = issue(previous.userId(), previous.provider());
        return Optional.of(new RotatedToken(previous, replacement));
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            tokensByHash.remove(hash(token));
        }
    }

    public void revokeAllForUser(UUID userId) {
        tokensByHash.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash refresh token", exception);
        }
    }

    public record StoredRefreshToken(UUID userId, String provider, Instant expiresAt) {
    }

    public record RotatedToken(StoredRefreshToken previous, String replacementToken) {
    }
}
