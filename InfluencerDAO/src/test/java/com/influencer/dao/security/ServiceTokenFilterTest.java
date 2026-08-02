package com.influencer.dao.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Proves the DAO is no longer an open CRUD API over the database.
 *
 * <p>Before this filter existed, anyone able to route to the DAO port could read or write every
 * table for every tenant with no credential at all.
 */
class ServiceTokenFilterTest {

    private static final String VALID_TOKEN = "correct-service-token";

    private MockHttpServletRequest request(String path, String presentedToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (presentedToken != null) {
            request.addHeader("X-Service-Token", presentedToken);
        }
        return request;
    }

    @Test
    @DisplayName("a request with the correct service token is passed through")
    void allowsValidToken() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/creators", VALID_TOKEN), response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a request with no service token is rejected with 401 and never reaches the app")
    void rejectsMissingToken() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/creators", null), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a request with a wrong service token is rejected with 401")
    void rejectsWrongToken() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/creators", "guessed-token"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("an unconfigured service token fails closed rather than allowing everyone")
    void failsClosedWhenTokenNotConfigured() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/creators", "anything"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the health endpoint stays reachable so liveness probes keep working")
    void allowsHealthWithoutToken() {
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN);

        assertThat(filter.shouldNotFilter(request("/health", null))).isTrue();
        assertThat(filter.shouldNotFilter(request("/creators", null))).isFalse();
    }
}
