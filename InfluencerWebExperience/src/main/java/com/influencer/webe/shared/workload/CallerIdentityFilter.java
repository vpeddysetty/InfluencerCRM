package com.influencer.webe.shared.workload;

import com.influencer.platform.observability.LogContext;
import com.influencer.platform.workload.WorkloadToken;
import com.influencer.platform.workload.WorkloadTokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Records which service relayed a request, when it can be verified (roadmap step 4).
 *
 * <p><b>What this establishes.</b> A bearer token says who a request is <em>for</em>. It says
 * nothing about who is <em>asking</em>: anything holding a valid user token looked exactly like the
 * DPS relaying that user's click. Verifying the DPS's workload token makes the caller a checkable
 * fact, and puts it in the logs next to the user it acted for.
 *
 * <p><b>Deliberately not enforcing yet.</b> A present-but-invalid token is rejected — that is a
 * forgery, an expired credential, or one minted for another audience, and none should pass. But an
 * <em>absent</em> token is allowed through, because the BFF is reachable directly by the bearer-mode
 * SPA that still exists, and requiring the header would break it. Making it mandatory is a one-line
 * change here, correct once every caller routes through the DPS — the same staged approach the DAO
 * uses, for the same reason: a hop every request crosses is not the place for a hard cutover.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CallerIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallerIdentityFilter.class);

    /** MDC key naming the verified upstream service. */
    private static final String CALLER = "caller";

    private final WorkloadTokenVerifier verifier;

    public CallerIdentityFilter(WorkloadTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String presented = request.getHeader(WorkloadToken.HEADER);

        if (presented != null && !presented.isBlank() && verifier.isConfigured()) {
            WorkloadTokenVerifier.Result result = verifier.verify(presented);
            WorkloadToken.Claims claims = result.claims();

            if (claims != null && result.legacy()) {
                // The signal that decides when the shared secret can be deleted. When these stop
                // appearing, every caller is signing asymmetrically.
                log.warn("Accepted a LEGACY HMAC workload token from '{}' on {} {}",
                        claims.issuer(), request.getMethod(), request.getRequestURI());
            }

            if (claims == null) {
                // Present but unverifiable. Refusing is the only safe reading: accepting it would
                // make the header decorative, and a decorative security header is worse than none
                // because it invites people to trust it.
                log.warn("Rejected an invalid workload token on {} {}",
                        request.getMethod(), request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"status\":403,\"error\":\"Forbidden\","
                                + "\"message\":\"Invalid workload token\"}");
                return;
            }

            LogContext.put(CALLER, claims.issuer());
            if (claims.requestId() != null) {
                // Adopt the caller's correlation id so one browser action keeps one id across the
                // DPS, this service, and the DAO.
                LogContext.put(LogContext.REQUEST_ID, claims.requestId());
            }
            log.debug("Verified caller '{}' for {} {}",
                    claims.issuer(), request.getMethod(), request.getRequestURI());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            org.slf4j.MDC.remove(CALLER);
            // Threads are pooled. A leaked verified tenant would let the NEXT request sign a
            // workload token for the previous caller's brand — a cross-tenant leak created by
            // cleanup that was merely incomplete.
            AuthoritativeTenant.clear();
        }
    }
}
