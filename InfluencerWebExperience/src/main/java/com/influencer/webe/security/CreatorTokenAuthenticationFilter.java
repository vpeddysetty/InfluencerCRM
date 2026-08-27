package com.influencer.webe.security;

import com.influencer.webe.shared.application.CreatorSessionVerifier;
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

/**
 * Resolves the {@code X-Creator-Token} header into an authenticated creator (roadmap PR-40).
 *
 * <p><b>Why this exists, when the controllers already checked the token.</b> They did — every
 * creator endpoint opened with {@code requireCreator(token)}, and every one of them got it right.
 * The problem is the shape of that arrangement rather than any instance of it: the creator paths
 * were {@code permitAll()} at the filter chain, so authentication was present only because each
 * author remembered to write it. One forgotten line is not a bug that returns the wrong data — it
 * is a fully unauthenticated endpoint serving unpublished pages, and it looks exactly like every
 * other endpoint on review. PR-41 through PR-44 add six to eight more handlers to that surface.
 *
 * <p>With this filter the paths become {@code authenticated()}, so a handler that forgets its check
 * <b>fails closed</b>: the request never reaches it. That is the entire point — not to replace the
 * controller checks, which still scope each request to a creator, but to remove the class of
 * mistake where forgetting one produces no visible symptom.
 *
 * <p><b>Two credentials, two filters, deliberately.</b> Teaching {@link JwtAuthenticationFilter}
 * about a second credential type would put creator rules inside the operator auth path, which is
 * precisely where they must not be — a creator has no account, no brand and no {@code
 * account_role}, and a bug that conflated the two would be a privilege escalation rather than a
 * mix-up. Each filter ignores the other's credential and neither can promote one into the other.
 *
 * <p>An absent or invalid token is left unauthenticated rather than rejected here, matching the
 * operator filter: the chain decides what an unauthenticated request may reach, so login and
 * signup keep working without this filter needing to know which paths those are.
 */
@Component
public class CreatorTokenAuthenticationFilter extends OncePerRequestFilter {

    /** The creator portal's credential. Opaque and server-side, never a JWT — see the service. */
    public static final String CREATOR_TOKEN_HEADER = "X-Creator-Token";

    /**
     * The authority a resolved creator holds, and the only one they ever hold.
     *
     * <p>Namespaced away from {@link Permission}'s vocabulary on purpose. A creator must never
     * satisfy a {@code hasAuthority} check written for operators, so this deliberately does not
     * look like {@code content:write} or any other operator permission — if the two namespaces
     * ever met, the failure would be silent and in the wrong direction.
     */
    public static final String CREATOR_AUTHORITY = "ROLE_CREATOR_PORTAL";

    private final CreatorSessionVerifier sessionVerifier;

    public CreatorTokenAuthenticationFilter(CreatorSessionVerifier sessionVerifier) {
        this.sessionVerifier = sessionVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Never overwrite an existing authentication. The operator filter runs first, and a
        // request carrying both credentials must keep the one already established rather than
        // being quietly downgraded to a creator — or, worse, upgraded.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = request.getHeader(CREATOR_TOKEN_HEADER);
            if (token != null && !token.isBlank()) {
                // The verifier re-reads the session store on every call, which is what makes
                // revocation immediate rather than effective at token expiry.
                Optional<UUID> creatorIdentityId = sessionVerifier.verifyCreatorToken(token);
                creatorIdentityId.ifPresent(value -> authenticate(request, value));
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, UUID creatorIdentityId) {
        // The principal is the creator's identity, never the token: downstream code needs to know
        // who is calling and must never have to handle the bearer value itself.
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                creatorIdentityId, null, List.of(new SimpleGrantedAuthority(CREATOR_AUTHORITY)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
