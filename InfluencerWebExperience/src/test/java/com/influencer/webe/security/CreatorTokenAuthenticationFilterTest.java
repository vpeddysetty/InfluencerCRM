package com.influencer.webe.security;

import com.influencer.webe.shared.application.CreatorSessionVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The creator credential resolves to an authentication, and never to anything more (PR-40).
 *
 * <p>The point of this filter is not that it authenticates — the controllers already did that. It
 * is that the paths can then be marked {@code authenticated()}, so a handler that forgets its own
 * check is refused by the chain instead of serving unpublished pages to anyone who asks. These
 * tests pin the properties that make that safe, and the two that would be dangerous to get wrong
 * are the last two: a creator must not displace an operator, and must not gain operator authority.
 *
 * <p>The filter depends on {@link CreatorSessionVerifier}, a port in {@code shared}, rather than on
 * Identity's service directly — {@code security} is cross-cutting and ArchUnit refuses a dependency
 * from it into any single context. That inversion is what makes this test a two-line stub instead
 * of a Spring context.
 */
class CreatorTokenAuthenticationFilterTest {

    private static final UUID CREATOR_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    /** Resolves exactly one token and nothing else. */
    private record StubVerifier(String validToken) implements CreatorSessionVerifier {

        @Override
        public Optional<UUID> verifyCreatorToken(String token) {
            return validToken != null && validToken.equals(token)
                    ? Optional.of(CREATOR_ID)
                    : Optional.empty();
        }
    }

    /** Records whether the request was allowed to continue. */
    private static class RecordingChain implements FilterChain {

        boolean called;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            called = true;
        }
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a valid creator token authenticates the request")
    void validTokenAuthenticates() throws Exception {
        Authentication authentication = runWithToken("good-token", "good-token");

        assertThat(authentication).isNotNull();
        // The principal is the creator identity, never the token — the port publishes nothing else.
        assertThat(authentication.getPrincipal()).isEqualTo(CREATOR_ID);
    }

    @Test
    @DisplayName("an unknown token leaves the request unauthenticated rather than rejecting it here")
    void unknownTokenIsNotAuthenticated() throws Exception {
        // Left to the chain, matching the operator filter. Rejecting here would mean this filter
        // had to know which paths are public, and login and signup would stop working.
        assertThat(runWithToken("good-token", "stale-token")).isNull();
    }

    @Test
    @DisplayName("no header at all is not an error")
    void absentHeaderPassesThrough() throws Exception {
        CreatorTokenAuthenticationFilter filter =
                new CreatorTokenAuthenticationFilter(new StubVerifier("good-token"));
        RecordingChain chain = new RecordingChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        // The request must still reach the chain: public paths have no credential by definition.
        assertThat(chain.called).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("an operator's authentication is never overwritten by a creator token")
    void doesNotOverwriteAnExistingAuthentication() throws Exception {
        // The dangerous direction. A request carrying both credentials must keep the operator's,
        // or a brand user could be silently downgraded to a creator and then refused on their own
        // endpoints — or, depending on the path, resolved as the wrong subject entirely.
        Authentication operator = new UsernamePasswordAuthenticationToken(
                "operator", null, List.of(new SimpleGrantedAuthority("creator:write")));
        SecurityContextHolder.getContext().setAuthentication(operator);

        assertThat(runWithToken("good-token", "good-token")).isSameAs(operator);
    }

    @Test
    @DisplayName("a creator holds one authority, and it is not an operator permission")
    void creatorAuthorityIsNamespacedAwayFromOperatorPermissions() throws Exception {
        // A creator must never satisfy a hasAuthority check written for operators. The authority
        // is deliberately shaped unlike Permission's vocabulary so the two namespaces cannot meet
        // by accident — a collision there would fail silently and in the wrong direction.
        Authentication authentication = runWithToken("good-token", "good-token");

        assertThat(authentication.getAuthorities()).hasSize(1);
        String authority = authentication.getAuthorities().iterator().next().getAuthority();
        assertThat(authority).isEqualTo(CreatorTokenAuthenticationFilter.CREATOR_AUTHORITY);
        // Permission uses a colon ("creator:write"); this deliberately does not.
        assertThat(authority).doesNotContain(":");
    }

    // ---- helpers -------------------------------------------------------

    private Authentication runWithToken(String validToken, String sentToken) throws Exception {
        CreatorTokenAuthenticationFilter filter =
                new CreatorTokenAuthenticationFilter(new StubVerifier(validToken));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CreatorTokenAuthenticationFilter.CREATOR_TOKEN_HEADER, sentToken);
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // Always assert the chain continued: a filter that swallows requests is a worse outage
        // than one that fails to authenticate them.
        assertThat(chain.called).isTrue();
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
