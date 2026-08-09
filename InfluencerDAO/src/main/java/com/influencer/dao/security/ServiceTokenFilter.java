package com.influencer.dao.security;

import com.influencer.platform.workload.WorkloadToken;
import com.influencer.platform.workload.WorkloadTokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Requires a shared service credential on every DAO request.
 *
 * <p>The DAO is an internal persistence API: its controllers expose direct CRUD over every table and
 * accept a tenancy key as an ordinary parameter. It was previously reachable by anyone who could
 * route to the port, which made it an unauthenticated read/write API over the entire database. Only
 * the BFF should be able to call it.
 *
 * <h2>Dual-accept: workload token preferred, shared secret still allowed</h2>
 *
 * <p>The shared secret it started with has four properties that make it the weakest link in the
 * chain: it never expires, it identifies nobody (the principal below was the literal
 * {@code "web-experience"} regardless of who called), it authorizes everything, and rotating it
 * means changing one value in every service simultaneously — which is why it never was rotated.
 *
 * <p>A {@link WorkloadToken} fixes all four: minutes-long expiry, a named issuer, an audience that
 * stops a captured token being replayed elsewhere, a scope, and a <em>signed</em> tenant id.
 *
 * <p><b>Both are accepted during the migration, and that is deliberate.</b> This filter sits on
 * every request the platform makes; a hard cutover means BFF and DAO must deploy in the same
 * instant or every call 401s, with no way to roll back one service alone. So the workload token is
 * tried first, the legacy secret second, and each legacy acceptance is logged at WARN with the
 * caller. When those warnings stop, the legacy branch is deleted in a one-line change — the point
 * of the logging is to make that removal evidence-based rather than a guess.
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final String expectedToken;
    private final WorkloadTokenVerifier verifier;

    public ServiceTokenFilter(@Value("${dao.service-token:}") String expectedToken,
                              @Value("${dao.workload.signing-key:}") String workloadKey,
                              @Value("${dao.workload.trust.web-experience:}") String bffPublicKey) {
        this.expectedToken = expectedToken;
        this.verifier = new WorkloadTokenVerifier(
                "dao", java.util.Map.of("web-experience", bffPublicKey), workloadKey);

        boolean hasLegacy = expectedToken != null && !expectedToken.isBlank();
        boolean hasWorkload = verifier.isConfigured();

        if (!hasLegacy && !hasWorkload) {
            log.error("Neither dao.service-token nor dao.workload.signing-key is configured. "
                    + "The DAO will reject all requests. Set dao.workload.signing-key here and "
                    + "web-experience.workload.signing-key on the BFF to the same value.");
        } else if (!hasWorkload) {
            log.warn("Workload identity is not configured; falling back to the shared service "
                    + "token for every call. Set dao.workload.signing-key to enable per-request "
                    + "caller identity, audience binding and signed tenancy.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!isAuthorized(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"status\":401,\"error\":\"Unauthorized\","
                            + "\"message\":\"A valid service token is required to call the DAO\"}");
            return;
        }

        // The principal is now whoever the token says, not a hard-coded literal. That is the
        // difference between an audit line naming the caller and one naming the only value this
        // filter ever knew how to write.
        WorkloadToken.Claims claims = verifiedClaims(request);
        String principal = claims == null ? "legacy-service" : claims.issuer();

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Correlation across the chain: the caller's request id becomes this service's, so one
        // browser action is one `rid` from the DPS through the BFF to here.
        if (claims != null && claims.requestId() != null) {
            org.slf4j.MDC.put("rid", claims.requestId());
            org.slf4j.MDC.put("caller", claims.issuer());
            if (claims.tenantId() != null) {
                org.slf4j.MDC.put("tenant", claims.tenantId());
            }
            // The VERIFIED scopes. CallerTenant reads cross-tenant permission from here, so it
            // can only ever be claimed by a signature — never by sending a header.
            if (claims.scope() != null && !claims.scope().isEmpty()) {
                org.slf4j.MDC.put(CallerTenant.MDC_SCOPE, String.join(" ", claims.scope()));
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            // Threads are pooled; without this the next request inherits the previous caller's
            // correlation id and logs under someone else's identity.
            org.slf4j.MDC.remove("rid");
            org.slf4j.MDC.remove("caller");
            org.slf4j.MDC.remove("tenant");
            org.slf4j.MDC.remove(CallerTenant.MDC_SCOPE);
        }
    }

    /** The verified workload claims, or null if the caller presented no valid workload token. */
    private WorkloadToken.Claims verifiedClaims(HttpServletRequest request) {
        return verifier.verify(request.getHeader(WorkloadToken.HEADER)).claims();
    }

    /**
     * Workload token first, legacy shared secret second.
     *
     * <p>Order matters: checking the legacy secret first would mean a caller sending both — which
     * every caller does during the migration — is recorded as a legacy acceptance forever, and the
     * WARN log that is supposed to signal "safe to remove the old path" would never go quiet.
     */
    private boolean isAuthorized(HttpServletRequest request) {
        if (verifiedClaims(request) != null) {
            return true;
        }

        // A workload token that was presented but did not verify is not a candidate for the legacy
        // path — it is a forgery, an expired token, or one minted for a different audience. Say so
        // rather than letting it fall through and be accepted on the strength of a shared secret.
        String presentedWorkload = request.getHeader(WorkloadToken.HEADER);
        if (presentedWorkload != null && !presentedWorkload.isBlank() && verifier.isConfigured()) {
            log.warn("Rejected an invalid workload token for {} {}",
                    request.getMethod(), request.getRequestURI());
            return false;
        }

        if (expectedToken == null || expectedToken.isBlank()) {
            // Fail closed: an unconfigured token must not mean "allow everyone".
            return false;
        }
        String presented = request.getHeader(SERVICE_TOKEN_HEADER);
        if (presented == null || presented.isBlank()) {
            return false;
        }
        boolean legacyValid = MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8));

        if (legacyValid) {
            // The signal that decides when the legacy branch can be deleted. When these stop
            // appearing, nothing is using the shared secret any more.
            log.warn("Accepted a LEGACY shared service token for {} {}. Configure "
                            + "web-experience.workload.signing-key on the caller to migrate.",
                    request.getMethod(), request.getRequestURI());
        }
        return legacyValid;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Liveness probes must work without the credential.
        return "/health".equals(request.getRequestURI());
    }
}
