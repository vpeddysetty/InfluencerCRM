package com.influencer.webe.service;

import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.security.TenantContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the caller's tenancy from a verified token, and only from a verified token.
 *
 * <p>This class once accepted a caller-supplied {@code userId} whenever no bearer token was present,
 * which let any client read or write any tenant's data by naming them. That fallback is gone: an
 * absent or invalid token is a 401, and a supplied id that disagrees with the token is a 403.
 *
 * <p>From Phase 2 the tenancy key is {@code brandId}. Any {@code brandId} arriving in a request is
 * only ever <em>validated</em> against the token's accessible-brand set — never trusted as identity.
 */
@Service
public class RequestUserResolver {

    private final SessionService sessionService;

    public RequestUserResolver(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * The active brand for this request — the tenancy key every data access must use.
     */
    public UUID resolveBrandId(String authorizationHeader) {
        return requireTenantContext(authorizationHeader).brandId();
    }

    /**
     * Validates a brand id supplied by the caller and returns the authoritative one.
     *
     * <p>A brand the caller cannot reach is a 403 — never a silent fallback to a brand they can,
     * which would quietly serve different data than requested.
     */
    public UUID resolveBrandId(String authorizationHeader, UUID explicitBrandId) {
        TenantContext context = requireTenantContext(authorizationHeader);
        if (explicitBrandId != null && !explicitBrandId.equals(context.brandId())) {
            if (!context.canAccessBrand(explicitBrandId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "You do not have access to the requested brand");
            }
            return explicitBrandId;
        }
        return context.brandId();
    }

    /** The authenticated user id. Identity for audit — never the tenancy key. */
    public UUID resolveUserId(String authorizationHeader) {
        return requireTenantContext(authorizationHeader).userId();
    }

    /**
     * Kept for call sites still passing a legacy {@code userId}: the value is checked against the
     * token and rejected on mismatch, but is never a source of identity.
     */
    public UUID resolveUserId(String authorizationHeader, UUID explicitUserId) {
        TenantContext context = requireTenantContext(authorizationHeader);
        if (explicitUserId != null && !explicitUserId.equals(context.userId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "userId does not match the authenticated user");
        }
        return context.userId();
    }

    /**
     * Resolves the full context, rejecting the request when authentication is missing.
     *
     * <p>Prefers the context published by {@code JwtAuthenticationFilter} so a request is not
     * re-parsed and re-verified per call site.
     */
    public TenantContext requireTenantContext(String authorizationHeader) {
        return TenantContextHolder.get()
                .or(() -> resolveTenantContext(authorizationHeader))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "A valid Authorization Bearer token is required"));
    }

    public TenantContext requireTenantContext(String authorizationHeader, UUID explicitUserId) {
        TenantContext context = requireTenantContext(authorizationHeader);
        if (explicitUserId != null && !explicitUserId.equals(context.userId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "userId does not match the authenticated user");
        }
        return context;
    }

    /**
     * Asserts a capability, returning the context so callers can use it in the same expression.
     *
     * <p>Checks a permission rather than a role: role checks spread across call sites are what make
     * an authorization model impossible to change later.
     */
    public TenantContext requirePermission(String authorizationHeader, Permission permission) {
        TenantContext context = requireTenantContext(authorizationHeader);
        if (!context.hasPermission(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role (" + context.role() + ") does not permit " + permission.key());
        }
        return context;
    }

    /** Asserts a capability and returns the active brand — the common shape at a call site. */
    public UUID requirePermissionForBrand(String authorizationHeader, Permission permission) {
        return requirePermission(authorizationHeader, permission).brandId();
    }

    public Optional<TenantContext> resolveTenantContext(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null) {
            return Optional.empty();
        }
        return sessionService.resolveTenantContext(token);
    }

    public Optional<SessionService.SessionInfo> resolveFromAuthorization(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null) {
            return Optional.empty();
        }
        return sessionService.resolve(token);
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.startsWith(prefix)) {
            return null;
        }
        String token = authorizationHeader.substring(prefix.length()).trim();
        return token.isBlank() ? null : token;
    }
}
