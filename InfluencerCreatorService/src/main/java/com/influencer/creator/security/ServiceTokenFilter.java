package com.influencer.creator.security;

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
 * <p>This is deliberately a shared secret rather than mTLS — it is the smallest change that closes
 * the hole now. Phase 5 replaces it with per-service identities when contexts become separately
 * deployed (docs/ddd-roadmap.md).
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final String expectedToken;

    public ServiceTokenFilter(@Value("${creator.service-token:}") String expectedToken) {
        this.expectedToken = expectedToken;
        if (expectedToken == null || expectedToken.isBlank()) {
            log.error("creator.service-token is not configured. The creator service will reject all requests. "
                    + "Set creator.service-token here and web-experience.dao-service-token on the BFF to the same value.");
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
                            + "\"message\":\"A valid service token is required to call the creator service\"}");
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "web-experience", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isAuthorized(HttpServletRequest request) {
        if (expectedToken == null || expectedToken.isBlank()) {
            // Fail closed: an unconfigured token must not mean "allow everyone".
            return false;
        }
        String presented = request.getHeader(SERVICE_TOKEN_HEADER);
        if (presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Liveness probes must work without the credential.
        return "/health".equals(request.getRequestURI());
    }
}
