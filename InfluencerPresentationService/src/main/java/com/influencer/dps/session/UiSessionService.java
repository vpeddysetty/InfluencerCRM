package com.influencer.dps.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.dps.cache.LoginCacheService;
import com.influencer.dps.config.DpsProperties;
import com.influencer.dps.identity.IdentityClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and maintains browser sessions.
 *
 * <p>This is where tokens stop travelling to the browser. Authentication is brokered to the BFF, the
 * resulting tokens are kept server-side, and the browser receives only an opaque session id.
 */
@Service
public class UiSessionService {

    private static final Logger log = LoggerFactory.getLogger(UiSessionService.class);
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final IdentityClient identityClient;
    private final SessionStore sessionStore;
    private final LoginCacheService loginCacheService;
    private final Duration ttl;

    public UiSessionService(IdentityClient identityClient,
                            SessionStore sessionStore,
                            LoginCacheService loginCacheService,
                            DpsProperties properties) {
        this.identityClient = identityClient;
        this.sessionStore = sessionStore;
        this.loginCacheService = loginCacheService;
        this.ttl = Duration.ofMinutes(properties.getSessionTtlMinutes());
    }

    public UiSession login(String email, String password) {
        return establish(identityClient.login(email, password));
    }

    public UiSession signup(String email, String password, String brandName, String accountType) {
        return signup(email, password, brandName, accountType, null, null, null);
    }

    /**
     * As above, carrying the consent from the sign-up form and the client that gave it.
     *
     * <p>The BFF requires consent and refuses a signup without it, so omitting these was not a
     * missing nicety — it broke email-and-password signup through the DPS outright.
     */
    public UiSession signup(String email,
                            String password,
                            String brandName,
                            String accountType,
                            Boolean acceptedTerms,
                            String clientIp,
                            String userAgent) {
        return establish(identityClient.signup(
                email, password, brandName, accountType, acceptedTerms, clientIp, userAgent));
    }

    /**
     * Completes a federated sign-in by redeeming the BFF's single-use handoff code.
     *
     * <p>Goes through the same {@code establish} path as password login, so a Google session is
     * identical to a password one from here on: same cookie, same server-side tokens, same warm
     * cache, same brand resolution. Federation changes how the user proved who they are, not what a
     * session is.
     */
    public UiSession completeOAuth(String handoffCode) {
        return establish(identityClient.redeemOAuthHandoff(handoffCode));
    }

    private UiSession establish(JsonNode auth) {
        String accessToken = text(auth, "accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication failed");
        }

        Instant now = Instant.now();
        String email = text(auth, "email");
        String inferredName = email != null && email.contains("@") ? email.split("@")[0] : email;

        UiSession session = new UiSession(
                newSessionId(),
                uuid(auth, "userId"),
                email,
                inferredName,
                accessToken,
                text(auth, "refreshToken"),
                uuid(auth, "accountId"),
                uuid(auth, "brandId"),
                text(auth, "brandName"),
                text(auth, "role"),
                // Permissions are read from the token the BFF minted. The DPS does not compute
                // them: two implementations of the permission matrix could disagree, and that
                // divergence is exactly what once locked FINANCE users out entirely.
                readPermissions(accessToken),
                List.of(),
                Map.of(),
                now, now, now.plus(ttl));

        session = withBrands(session);
        // The login-time cache hook. No warmers registered yet, so this is currently a no-op.
        session = loginCacheService.warm(session);

        sessionStore.save(session);
        log.info("Session established for {} (brand {})", session.email(), session.brandId());
        return session;
    }

    private UiSession withBrands(UiSession session) {
        try {
            JsonNode brands = identityClient.listBrands(session.accessToken());
            List<UiSession.BrandSummary> summaries = new ArrayList<>();
            if (brands != null && brands.isArray()) {
                for (JsonNode node : brands) {
                    summaries.add(new UiSession.BrandSummary(
                            uuid(node, "brandId"),
                            text(node, "brandName"),
                            text(node, "role"),
                            node.path("active").asBoolean(false)));
                }
            }
            return session.withBrands(summaries);
        } catch (Exception exception) {
            // Non-fatal: the workspace still works against the brand already in the token, and a
            // brand-list hiccup should not prevent signing in.
            log.warn("Could not load brands for {}; continuing without the switcher", session.email());
            return session;
        }
    }

    public Optional<UiSession> find(String sessionId) {
        return sessionStore.find(sessionId);
    }

    /**
     * Returns a session with a valid access token, refreshing transparently if it has expired.
     *
     * <p>The browser never sees this happen — which is the point of holding the tokens here.
     */
    public Optional<UiSession> findRefreshed(String sessionId) {
        Optional<UiSession> found = sessionStore.find(sessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        UiSession session = found.get();
        if (!isAccessTokenExpired(session.accessToken())) {
            return Optional.of(session);
        }

        try {
            JsonNode refreshed = identityClient.refresh(session.refreshToken());
            String newAccess = text(refreshed, "accessToken");
            if (newAccess == null || newAccess.isBlank()) {
                sessionStore.delete(sessionId);
                return Optional.empty();
            }
            String newRefresh = text(refreshed, "refreshToken");
            UiSession updated = session.withTokens(
                    newAccess,
                    newRefresh == null || newRefresh.isBlank() ? session.refreshToken() : newRefresh);
            sessionStore.save(updated);
            return Optional.of(updated);
        } catch (Exception exception) {
            // A refresh token that no longer works means the session is over. Deleting it here
            // turns the next request into a clean 401 rather than a confusing loop.
            log.info("Refresh failed for session {}; ending it", sessionId);
            sessionStore.delete(sessionId);
            return Optional.empty();
        }
    }

    /**
     * Re-scopes the session to another brand.
     *
     * <p>The BFF re-mints the token because role and permissions are per-brand; carrying the old
     * brand's role across would over-grant. The warm cache is dropped for the same reason.
     */
    public UiSession switchBrand(UiSession session, UUID brandId) {
        JsonNode response = identityClient.switchBrand(session.accessToken(), brandId.toString());
        String newAccess = text(response, "accessToken");
        if (newAccess == null || newAccess.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unable to switch to that brand");
        }

        UiSession updated = session.withBrand(
                brandId,
                text(response, "brandName"),
                text(response, "role"),
                readPermissions(newAccess),
                newAccess);

        updated = loginCacheService.warm(updated);
        sessionStore.save(updated);
        return updated;
    }

    public void logout(UiSession session) {
        if (session.refreshToken() != null) {
            identityClient.logout(session.refreshToken());
        }
        sessionStore.delete(session.sessionId());
    }

    public int logoutEverywhere(UUID userId) {
        return sessionStore.deleteAllForUser(userId);
    }

    public long activeSessions() {
        return sessionStore.size();
    }

    // ------------------------------------------------------------------ helpers

    private String newSessionId() {
        // 256 bits: the session id is a bearer credential in its own right, so it must not be
        // guessable.
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Whether the access token has expired, read from its {@code exp} claim.
     *
     * <p>Decoded, not verified — the BFF verifies. This only decides whether to refresh early, and a
     * tampered token would fail at the BFF anyway. A 30-second skew avoids sending a token that
     * expires in flight.
     */
    private boolean isAccessTokenExpired(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return true;
        }
        try {
            String payload = accessToken.split("\\.")[1];
            JsonNode claims = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Base64.getUrlDecoder().decode(padBase64(payload)));
            long exp = claims.path("exp").asLong(0);
            return exp == 0 || Instant.ofEpochSecond(exp).isBefore(Instant.now().plusSeconds(30));
        } catch (Exception exception) {
            return true;
        }
    }

    private List<String> readPermissions(String accessToken) {
        try {
            String payload = accessToken.split("\\.")[1];
            JsonNode claims = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Base64.getUrlDecoder().decode(padBase64(payload)));
            List<String> permissions = new ArrayList<>();
            claims.path("perms").forEach(node -> permissions.add(node.asText()));
            return List.copyOf(permissions);
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** JWT segments omit Base64 padding; the JDK decoder requires it. */
    private String padBase64(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "====".substring(remainder);
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
