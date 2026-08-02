package com.influencer.webe.service;

import com.influencer.webe.client.DaoTenancyClient;
import com.influencer.webe.security.AccountRole;
import com.influencer.webe.security.JwtService;
import com.influencer.webe.security.RefreshTokenStore;
import com.influencer.webe.security.RolePermissions;
import com.influencer.webe.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Creates and resolves authenticated sessions, scoped to an active brand.
 *
 * <p>Sessions are signed RS256 JWTs verified by signature alone, paired with a revocable refresh
 * token. The token carries the caller's active brand plus every brand they may switch to, so
 * ordinary requests need no tenancy lookup — only an actual brand switch re-reads the spine.
 */
@Service
public class SessionService {

    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final DaoTenancyClient tenancyClient;

    public SessionService(JwtService jwtService,
                          RefreshTokenStore refreshTokenStore,
                          DaoTenancyClient tenancyClient) {
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.tenancyClient = tenancyClient;
    }

    /**
     * Issues a session for a freshly authenticated user, defaulting to their first accessible brand.
     */
    public SessionInfo createSession(UUID userId, String email, String provider) {
        return createSession(userId, email, provider, null);
    }

    /**
     * Issues a session scoped to {@code requestedBrandId} when supplied.
     *
     * <p>A requested brand the caller cannot reach is a 403 rather than a silent fallback to a brand
     * they can — quietly serving different data than asked for is how cross-tenant confusion starts.
     */
    public SessionInfo createSession(UUID userId, String email, String provider, UUID requestedBrandId) {
        List<DaoTenancyClient.BrandAccess> brands = tenancyClient.findAccessibleBrands(userId);
        if (brands.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This user has no accessible brands. An account, brand and membership are required.");
        }

        DaoTenancyClient.BrandAccess active = brands.get(0);
        if (requestedBrandId != null) {
            active = brands.stream()
                    .filter(b -> b.brandId().equals(requestedBrandId))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "You do not have access to the requested brand"));
        }

        TenantContext context = toContext(userId, email, active, brands);
        String accessToken = jwtService.issueAccessToken(context, provider);
        String refreshToken = refreshTokenStore.issue(userId, provider);
        Instant now = Instant.now();

        return new SessionInfo(
                accessToken,
                refreshToken,
                userId,
                email,
                provider,
                active.brandId(),
                active.brandName(),
                active.accountId(),
                active.role().name(),
                now,
                now.plus(jwtService.getAccessTokenTtl()));
    }

    /**
     * Re-mints the caller's token against a different brand.
     *
     * <p>Access is re-read from the DAO rather than trusted from the presented token, so a
     * membership revoked mid-session cannot be used to switch into a brand the caller has lost.
     * Role and permissions are recomputed for the target brand, since they are per-brand.
     */
    public SessionInfo switchBrand(TenantContext current, UUID targetBrandId, String provider) {
        List<DaoTenancyClient.BrandAccess> brands = tenancyClient.findAccessibleBrands(current.userId());
        DaoTenancyClient.BrandAccess target = brands.stream()
                .filter(b -> b.brandId().equals(targetBrandId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "You do not have access to the requested brand"));

        TenantContext context = toContext(current.userId(), current.email(), target, brands);
        Instant now = Instant.now();
        return new SessionInfo(
                jwtService.issueAccessToken(context, provider),
                null,
                current.userId(),
                current.email(),
                provider,
                target.brandId(),
                target.brandName(),
                target.accountId(),
                target.role().name(),
                now,
                now.plus(jwtService.getAccessTokenTtl()));
    }

    /** Verifies an access token. Empty means unauthenticated — never fall back to caller-supplied identity. */
    public Optional<TenantContext> resolveTenantContext(String token) {
        return jwtService.verify(token);
    }

    public Optional<SessionInfo> resolve(String token) {
        return jwtService.verify(token).map(context -> new SessionInfo(
                token, null, context.userId(), context.email(), null,
                context.brandId(), null, context.accountId(),
                context.role() == null ? null : context.role().name(),
                Instant.now(), Instant.now().plus(jwtService.getAccessTokenTtl())));
    }

    /**
     * Reads the owning user of a refresh token without consuming it, so the caller can load the user
     * record before rotation and mint a token with correct claims.
     */
    public Optional<UUID> peekRefreshTokenUserId(String refreshToken) {
        return refreshTokenStore.resolve(refreshToken).map(RefreshTokenStore.StoredRefreshToken::userId);
    }

    /** Exchanges a refresh token for a new access token, rotating the refresh token in the process. */
    public Optional<SessionInfo> refresh(String refreshToken, String email) {
        return refreshTokenStore.rotate(refreshToken).map(rotated -> {
            UUID userId = rotated.previous().userId();
            String provider = rotated.previous().provider();

            // Brand access is re-read on every refresh, so a revoked membership stops working
            // within one access-token lifetime rather than persisting for the refresh window.
            List<DaoTenancyClient.BrandAccess> brands = tenancyClient.findAccessibleBrands(userId);
            if (brands.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This user no longer has access to any brand");
            }
            DaoTenancyClient.BrandAccess active = brands.get(0);
            TenantContext context = toContext(userId, email, active, brands);

            Instant now = Instant.now();
            return new SessionInfo(
                    jwtService.issueAccessToken(context, provider),
                    rotated.replacementToken(),
                    userId,
                    email,
                    provider,
                    active.brandId(),
                    active.brandName(),
                    active.accountId(),
                    active.role().name(),
                    now,
                    now.plus(jwtService.getAccessTokenTtl()));
        });
    }

    /**
     * Revokes the session's refresh token.
     *
     * <p>The access token remains valid until it expires — the accepted trade-off of stateless
     * verification, bounded by the short access-token TTL.
     */
    public void invalidate(String refreshToken) {
        refreshTokenStore.revoke(refreshToken);
    }

    public void invalidateAllForUser(UUID userId) {
        refreshTokenStore.revokeAllForUser(userId);
    }

    private TenantContext toContext(UUID userId,
                                    String email,
                                    DaoTenancyClient.BrandAccess active,
                                    List<DaoTenancyClient.BrandAccess> allBrands) {
        Set<UUID> accessibleBrandIds = new LinkedHashSet<>();
        allBrands.forEach(b -> accessibleBrandIds.add(b.brandId()));

        AccountRole role = active.role();
        return new TenantContext(
                userId,
                active.accountId(),
                active.brandId(),
                email,
                role,
                RolePermissions.forRole(role),
                accessibleBrandIds);
    }

    public record SessionInfo(
            String token,
            String refreshToken,
            UUID userId,
            String email,
            String provider,
            UUID brandId,
            String brandName,
            UUID accountId,
            String role,
            Instant issuedAt,
            Instant expiresAt) {
    }
}
