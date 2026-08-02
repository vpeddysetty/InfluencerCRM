package com.influencer.dps.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A browser session, held server-side.
 *
 * <p>The tokens live in this object and never leave the service. The browser holds only an opaque
 * session id in an httpOnly cookie, so JavaScript — and therefore any XSS payload — cannot read a
 * credential. That is the whole reason the session moved out of the React layer.
 *
 * @param accessToken  the bearer token used for downstream calls. Never serialised to the browser.
 * @param refreshToken likewise; rotation happens entirely inside the DPS.
 * @param warmCache    data assembled once at login so remotes do not each re-fetch it. Empty until
 *                     a {@code LoginCacheWarmer} populates it — the extension point for the
 *                     login-time cache requirement.
 */
public record UiSession(
        String sessionId,
        UUID userId,
        String email,
        String userName,
        String accessToken,
        String refreshToken,
        UUID accountId,
        UUID brandId,
        String brandName,
        String role,
        List<String> permissions,
        List<BrandSummary> availableBrands,
        Map<String, Object> warmCache,
        Instant createdAt,
        Instant lastAccessedAt,
        Instant expiresAt) {

    public record BrandSummary(UUID brandId, String brandName, String role, boolean active) {
    }

    public boolean isExpired(Instant now) {
        return expiresAt == null || expiresAt.isBefore(now);
    }

    /** Sliding expiry: activity extends the session, idleness lets it lapse. */
    public UiSession touch(Instant now, java.time.Duration ttl) {
        return new UiSession(sessionId, userId, email, userName, accessToken, refreshToken,
                accountId, brandId, brandName, role, permissions, availableBrands, warmCache,
                createdAt, now, now.plus(ttl));
    }

    /** Replaces the tokens after a refresh, leaving everything else — including the cache — intact. */
    public UiSession withTokens(String newAccessToken, String newRefreshToken) {
        return new UiSession(sessionId, userId, email, userName, newAccessToken, newRefreshToken,
                accountId, brandId, brandName, role, permissions, availableBrands, warmCache,
                createdAt, lastAccessedAt, expiresAt);
    }

    /**
     * Re-scopes the session to another brand.
     *
     * <p>The warm cache is dropped: it was built for the previous brand, and serving one brand's
     * cached data under another's name is exactly the cross-tenant leak this platform spent Phase 2
     * eliminating.
     */
    public UiSession withBrand(UUID newBrandId,
                               String newBrandName,
                               String newRole,
                               List<String> newPermissions,
                               String newAccessToken) {
        return new UiSession(sessionId, userId, email, userName, newAccessToken, refreshToken,
                accountId, newBrandId, newBrandName, newRole, newPermissions, availableBrands,
                Map.of(),
                createdAt, lastAccessedAt, expiresAt);
    }

    public UiSession withWarmCache(Map<String, Object> cache) {
        return new UiSession(sessionId, userId, email, userName, accessToken, refreshToken,
                accountId, brandId, brandName, role, permissions, availableBrands, cache,
                createdAt, lastAccessedAt, expiresAt);
    }

    public UiSession withBrands(List<BrandSummary> brands) {
        return new UiSession(sessionId, userId, email, userName, accessToken, refreshToken,
                accountId, brandId, brandName, role, permissions, brands, warmCache,
                createdAt, lastAccessedAt, expiresAt);
    }
}
