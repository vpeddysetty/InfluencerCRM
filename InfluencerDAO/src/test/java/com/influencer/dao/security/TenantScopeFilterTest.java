package com.influencer.dao.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The other half of the IDOR: a tenant-scoped read that names no tenant.
 *
 * <p>{@link SignedTenantArgumentResolver} stops {@code ?brandId=<someone-else>}. This stops
 * {@code GET /creators} with no parameter at all, which fell through to {@code findAll()} and
 * returned every customer's rows — the abuse that is the ABSENCE of a parameter, so no validation
 * written on the parameter ever ran.
 */
class TenantScopeFilterTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    @Test
    @DisplayName("an unscoped list is refused when enforcement is on")
    void refusesAnUnscopedList() throws Exception {
        TenantScopeFilter filter = new TenantScopeFilter(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/creators"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("a signed tenant satisfies the requirement without any query parameter")
    void signedTenantIsEnough() throws Exception {
        // The caller does not have to name its brand — the token already did.
        MDC.put("tenant", TENANT);
        TenantScopeFilter filter = new TenantScopeFilter(true);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/creators"), new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("enforcement is off by default, and says so")
    void offByDefault() throws Exception {
        // Refusing requests that work today must be a deliberate act, not something a deploy
        // brings with it.
        TenantScopeFilter filter = new TenantScopeFilter(false);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/creators"), new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("tenancy and identity paths are exempt")
    void exemptPaths() throws Exception {
        // Creating an account is how a brand comes to exist; requiring one would be circular.
        TenantScopeFilter filter = new TenantScopeFilter(true);

        for (String path : new String[]{"/tenancy/accounts", "/users", "/memberships", "/health"}) {
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(get(path), new MockHttpServletResponse(), chain);
            verify(chain, times(1)).doFilter(any(), any());
        }
    }

    @Test
    @DisplayName("a by-id fetch is not treated as an unscoped list")
    void byIdIsAllowed() throws Exception {
        // Single-row reads have their own ownership check, and the IDOR being closed here is the
        // unfiltered LIST. Requiring a brand would break every by-id read for no gain.
        TenantScopeFilter filter = new TenantScopeFilter(true);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/creators/" + TENANT), new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("writes are not blocked here")
    void writesPassThrough() throws Exception {
        // A POST carries its tenant in the body, which this filter deliberately does not parse:
        // consuming the body in a filter breaks the controller's own binding.
        TenantScopeFilter filter = new TenantScopeFilter(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/creators");
        post.setRequestURI("/creators");

        filter.doFilter(post, new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("an explicit brandId still works while callers have no signed tenant")
    void explicitBrandStillWorks() throws Exception {
        TenantScopeFilter filter = new TenantScopeFilter(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = get("/creators");
        request.setParameter("brandId", TENANT);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("a background sweep with the dao:sweep scope may read across tenants")
    void sweepScopeAllowsCrossTenantRead() throws Exception {
        // HostingExpiryScheduler warns EVERY brand's owners, so it has no tenant to sign. Without
        // this it would start failing under enforcement and the only symptom would be that warning
        // emails quietly stopped — the worst kind of broken.
        MDC.put("callerScope", "dao:read dao:write dao:sweep");
        TenantScopeFilter filter = new TenantScopeFilter(true);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/landing-templates"), new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("an ordinary caller's scopes do not grant a cross-tenant read")
    void ordinaryScopesDoNotAllowIt() throws Exception {
        // The scope has to be claimed deliberately. If dao:read were enough, every request would
        // hold it and the control would mean nothing.
        MDC.put("callerScope", "dao:read dao:write");
        TenantScopeFilter filter = new TenantScopeFilter(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/landing-templates"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("the sweep scope cannot be claimed by an unsigned caller")
    void unsignedCallerHasNoScopes() throws Exception {
        // Scopes are written to MDC only from VERIFIED claims, so there is no header to send. This
        // pins that an absent scope context is not treated as permissive.
        TenantScopeFilter filter = new TenantScopeFilter(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/landing-templates"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(400);
    }
}
