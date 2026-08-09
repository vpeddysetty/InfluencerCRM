package com.influencer.webe.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.config.WebExperienceProperties;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds refresh tokens so that sessions remain revocable while access tokens stay stateless.
 *
 * <p>Access tokens are short-lived and verified by signature alone. Revocation therefore happens
 * here: dropping the refresh token stops the session being renewed, bounded by the access-token TTL.
 *
 * <p>Previously a {@code ConcurrentHashMap}. That was survivable with one process — losing it merely
 * forced a re-login at the next refresh — but it breaks the moment a second instance exists, because
 * instance B cannot see a token issued by instance A. The user experiences an apparently random
 * logout that reads as a session bug rather than a topology one. Tokens now live in Postgres via the
 * DAO, so any instance can resolve any session.
 *
 * <p><strong>Only the hash is ever persisted.</strong> The raw token is returned to the caller once
 * and never stored, so neither the database nor the internal API can yield a usable credential.
 */
@Component
public class RefreshTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DaoGatewayClient daoGatewayClient;
    private final Duration refreshTokenTtl;

    public RefreshTokenStore(WebExperienceProperties properties, DaoGatewayClient daoGatewayClient) {
        this.daoGatewayClient = daoGatewayClient;
        this.refreshTokenTtl = Duration.ofMinutes(properties.getRefreshTokenTtlMinutes());
    }

    /** Returns the raw token to hand to the client; only its hash is retained. */
    public String issue(UUID userId, String provider) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("tokenHash", hash(token));
        payload.put("userId", userId.toString());
        payload.put("provider", provider);
        payload.put("expiresAt", Instant.now().plus(refreshTokenTtl).toString());

        daoGatewayClient.post("/refresh-tokens", payload);
        return token;
    }

    public Optional<StoredRefreshToken> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = daoGatewayClient.get("/refresh-tokens/" + hash(token), null);
            if (node == null || node.isNull() || !node.hasNonNull("userId")) {
                return Optional.empty();
            }
            return Optional.of(new StoredRefreshToken(
                    UUID.fromString(node.get("userId").asText()),
                    node.hasNonNull("provider") ? node.get("provider").asText() : null,
                    Instant.parse(node.get("expiresAt").asText())));
        } catch (Exception exception) {
            // Unknown, expired and revoked are indistinguishable to a caller deciding whether to
            // renew, and the DAO returns 404 for all three. Anything else here is also a reason not
            // to renew, so failing closed is correct.
            return Optional.empty();
        }
    }

    /** Rotates on use: the presented token is consumed and a fresh one returned. */
    public Optional<RotatedToken> rotate(String presentedToken) {
        Optional<StoredRefreshToken> stored = resolve(presentedToken);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        revoke(presentedToken);

        StoredRefreshToken previous = stored.get();
        String replacement = issue(previous.userId(), previous.provider());
        return Optional.of(new RotatedToken(previous, replacement));
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            daoGatewayClient.delete("/refresh-tokens/" + hash(token));
        } catch (Exception exception) {
            // Logout must not fail because the token was already gone: the end state the caller
            // asked for — no live session — already holds.
        }
    }

    public void revokeAllForUser(UUID userId) {
        if (userId == null) {
            return;
        }
        daoGatewayClient.delete("/refresh-tokens/users/" + userId);
    }

    /**
     * URL-safe SHA-256, because the hash travels in a path segment. Standard Base64 would emit
     * {@code /} and {@code +}, which would break the route.
     */
    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash refresh token", exception);
        }
    }

    public record StoredRefreshToken(UUID userId, String provider, Instant expiresAt) {
    }

    public record RotatedToken(StoredRefreshToken previous, String replacementToken) {
    }
}
