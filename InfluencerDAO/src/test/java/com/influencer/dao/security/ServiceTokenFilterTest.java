package com.influencer.dao.security;

import com.influencer.platform.workload.WorkloadKeyPairGenerator;
import com.influencer.platform.workload.WorkloadToken;
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
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN, "", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/creators", VALID_TOKEN), response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a request with no service token is rejected with 401 and never reaches the app")
    void rejectsMissingToken() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN, "", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/creators", null), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a request with a wrong service token is rejected with 401")
    void rejectsWrongToken() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN, "", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/creators", "guessed-token"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("an unconfigured service token fails closed rather than allowing everyone")
    void failsClosedWhenTokenNotConfigured() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("", "", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/creators", "anything"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the health endpoint stays reachable so liveness probes keep working")
    void allowsHealthWithoutToken() {
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN, "", "");

        assertThat(filter.shouldNotFilter(request("/health", null))).isTrue();
        assertThat(filter.shouldNotFilter(request("/creators", null))).isFalse();
    }

    // ---- dual-accept: workload token preferred, legacy still allowed (step 3) ----------------

    private static final String WORKLOAD_KEY = "a-workload-signing-key-long-enough-for-hmac-use";

    private String workloadToken(String audience, String tenant, String key) {
        return WorkloadToken.issue("web-experience", audience,
                java.util.Set.of("dao:read"), tenant, "req-test-1", key, java.time.Instant.now());
    }

    private MockHttpServletRequest withWorkload(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (token != null) {
            request.addHeader(WorkloadToken.HEADER, token);
        }
        return request;
    }

    @Test
    @DisplayName("a valid workload token is accepted without the legacy secret")
    void acceptsAWorkloadToken() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("", WORKLOAD_KEY, "");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withWorkload("/creators", workloadToken("dao", "brand-1", WORKLOAD_KEY)),
                response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a workload token minted for another service is refused")
    void refusesTheWrongAudience() throws Exception {
        // Without the audience check, a token captured on the BFF path replays here.
        ServiceTokenFilter filter = new ServiceTokenFilter("", WORKLOAD_KEY, "");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withWorkload("/creators", workloadToken("bff", "brand-1", WORKLOAD_KEY)),
                response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a forged workload token is refused and does NOT fall back to the legacy secret")
    void aForgedWorkloadTokenDoesNotFallBack() throws Exception {
        // The subtle one. If an invalid workload token fell through to the legacy check, an
        // attacker who also knew the shared secret would have their forgery quietly accepted —
        // and the audit line would name whoever the forged token claimed to be.
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN, WORKLOAD_KEY, "");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockHttpServletRequest request = withWorkload("/creators",
                workloadToken("dao", "brand-1", "a-completely-different-signing-key-here!!"));
        request.addHeader("X-Service-Token", VALID_TOKEN);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the legacy secret still works while the workload key is configured")
    void legacyStillWorksDuringMigration() throws Exception {
        // The whole point of dual-accept: a rolling deploy where the BFF has not yet been given a
        // key must keep working, or the cutover is an outage.
        ServiceTokenFilter filter = new ServiceTokenFilter(VALID_TOKEN, WORKLOAD_KEY, "");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/creators", VALID_TOKEN), response, chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("with neither credential configured, everything is refused")
    void failsClosedWithNoCredentialsConfigured() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("", "", "");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withWorkload("/creators", workloadToken("dao", "b", WORKLOAD_KEY)),
                response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ---- asymmetric: the verifier cannot mint ---------------------------------------------

    private static final WorkloadKeyPairGenerator.Pair BFF = WorkloadKeyPairGenerator.generate();

    @Test
    @DisplayName("an Ed25519-signed token from a trusted issuer is accepted")
    void acceptsASignedToken() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("", "", BFF.publicKey());
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String token = WorkloadToken.issueSigned("web-experience", "dao",
                java.util.Set.of("dao:read"), "brand-1", "req-1",
                BFF.privateKey(), java.time.Instant.now());

        filter.doFilter(withWorkload("/creators", token), response, chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("holding only the DAO's config does not let you forge a BFF token")
    void theDaoConfigCannotForge() throws Exception {
        // The substantive gain over HMAC. The DAO's configuration contains BFF.publicKey() and
        // nothing else, so an attacker who reads it has no way to produce a token this filter
        // accepts — under the shared secret, that same config WAS the signing key.
        WorkloadKeyPairGenerator.Pair attacker = WorkloadKeyPairGenerator.generate();
        ServiceTokenFilter filter = new ServiceTokenFilter("", "", BFF.publicKey());
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String forged = WorkloadToken.issueSigned("web-experience", "dao",
                java.util.Set.of("dao:read"), "brand-1", "req-1",
                attacker.privateKey(), java.time.Instant.now());

        filter.doFilter(withWorkload("/creators", forged), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the signed tenant reaches the request context")
    void exposesTheSignedTenant() throws Exception {
        // What closes the optional-brandId IDOR: the tenant arrives as evidence rather than as a
        // query parameter the caller chose.
        ServiceTokenFilter filter = new ServiceTokenFilter("", "", BFF.publicKey());
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] seen = new String[1];

        FilterChain capture = (req, res) -> seen[0] = org.slf4j.MDC.get("tenant");

        String token = WorkloadToken.issueSigned("web-experience", "dao",
                java.util.Set.of("dao:read"), "brand-77", "req-1",
                BFF.privateKey(), java.time.Instant.now());

        filter.doFilter(withWorkload("/creators", token), response, capture);

        assertThat(seen[0]).isEqualTo("brand-77");
    }
}
