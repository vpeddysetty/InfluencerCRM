package com.influencer.webe.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Verifies the bearer token once per request and publishes the resulting {@link TenantContext}.
 *
 * <p>Resolution happens here and only here, so downstream code reads a trusted context rather than
 * re-parsing headers or trusting request parameters. A missing or invalid token leaves the security
 * context empty; the filter chain then rejects the request unless the path is on the public
 * allowlist in {@link SecurityConfig}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    /** Selects the active brand for a request, within the set the token already permits. */
    public static final String BRAND_HEADER = "X-Brand-Id";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            Optional<TenantContext> verified = extractBearerToken(request).flatMap(jwtService::verify);
            if (verified.isPresent()) {
                Optional<TenantContext> scoped = applyRequestedBrand(request, verified.get());
                if (scoped.isEmpty()) {
                    // The caller named a brand they cannot reach. Reject here rather than silently
                    // serving their default brand's data under a different brand's name.
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"status\":403,\"error\":\"Forbidden\","
                                    + "\"message\":\"You do not have access to the requested brand\"}");
                    return;
                }
                authenticate(request, scoped.get());
            }

            filterChain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled; leaving the ThreadLocal set would leak one caller's
            // tenancy into the next request served by this thread.
            TenantContextHolder.clear();
        }
    }

    /**
     * Applies the {@code X-Brand-Id} header, letting one token serve any brand the caller may reach.
     *
     * <p>Without this the UI would have to re-authenticate on every brand switch. The header is only
     * ever <em>validated</em> against the token's brand set — an unreachable brand yields empty, and
     * the caller gets a 403.
     *
     * <p>Role and permissions are recomputed for the target brand, because the same person can hold
     * different roles on different brands within one account.
     */
    private Optional<TenantContext> applyRequestedBrand(HttpServletRequest request, TenantContext context) {
        String header = request.getHeader(BRAND_HEADER);
        if (header == null || header.isBlank()) {
            return Optional.of(context);
        }

        UUID requestedBrandId;
        try {
            requestedBrandId = UUID.fromString(header.trim());
        } catch (IllegalArgumentException exception) {
            // A malformed header is not a valid claim to any brand.
            return Optional.empty();
        }

        if (requestedBrandId.equals(context.brandId())) {
            return Optional.of(context);
        }
        if (!context.canAccessBrand(requestedBrandId)) {
            return Optional.empty();
        }

        // The token carries the role for its active brand only. Switching brands within a request
        // must not silently carry that role across, so anything beyond the token's own brand is
        // narrowed to the account-wide roles which apply uniformly; a per-brand role difference
        // requires a real switch through /api/auth/switch-brand, which re-reads the spine.
        AccountRole roleForBrand = context.role() != null && context.role().impliesAllBrands()
                ? context.role()
                : null;
        if (roleForBrand == null) {
            return Optional.empty();
        }
        return Optional.of(context.withBrand(requestedBrandId, roleForBrand));
    }

    private void authenticate(HttpServletRequest request, TenantContext context) {
        List<SimpleGrantedAuthority> authorities = context.permissionKeys().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_" + context.role()));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(context, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        TenantContextHolder.set(context);
        request.setAttribute(TenantContextHolder.REQUEST_ATTRIBUTE, context);
    }

    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }
}
