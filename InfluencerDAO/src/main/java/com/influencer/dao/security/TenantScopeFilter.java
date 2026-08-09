package com.influencer.dao.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Refuses a tenant-scoped read that names no tenant.
 *
 * <h2>The half of the IDOR the argument resolver does not close</h2>
 *
 * <p>{@link SignedTenantArgumentResolver} decides <em>which</em> brand a caller may name: it
 * substitutes the signed tenant for whatever the query string said. That stops
 * {@code ?brandId=<someone-else>}.
 *
 * <p>It does nothing about the other abuse, which is worse because it is the <em>absence</em> of a
 * parameter rather than a wrong value. Twenty-four controllers read
 *
 * <pre>
 *   if (brandId != null) return repository.findByBrandId(brandId);
 *   return repository.findAll();          // every tenant's rows
 * </pre>
 *
 * <p>so {@code GET /creators} with no parameter at all returned the entire table across every
 * customer. Any validation written on {@code brandId} never runs when {@code brandId} is not there.
 *
 * <h2>Why a filter rather than editing the controllers</h2>
 *
 * <p>Deleting the fallback in twenty-four files is twenty-four chances to do it slightly
 * differently, and the twenty-fifth controller — written next month by someone who has never read
 * this — would silently not have it. A filter is one rule that also covers the endpoints nobody has
 * written yet, which is the property that matters for a class of bug that keeps coming back.
 *
 * <p>It also keeps the controllers honest about what they are: they remain simple repository
 * façades, and the tenancy rule lives in one place where it can be read, tested, and changed.
 *
 * <h2>What is exempt, and why each one is</h2>
 *
 * <p>Three kinds of path legitimately have no brand:
 * <ul>
 *   <li><b>Tenancy administration</b> ({@code /tenancy/**}) — creating an account or listing its
 *       brands is how a brand comes to exist; requiring one would be circular.</li>
 *   <li><b>Identity</b> ({@code /users/**}, {@code /memberships/**}) — a user exists across brands,
 *       and login must work before any brand is selected.</li>
 *   <li><b>Infrastructure</b> ({@code /health}, {@code /actuator/**}) — no data.</li>
 * </ul>
 *
 * <p>The list is an allow-list rather than a deny-list on purpose: a new tenant-scoped endpoint is
 * protected by default and only an explicit addition here opts it out, which is a visible act in a
 * diff. A deny-list would leave every future endpoint unprotected until someone remembered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantScopeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantScopeFilter.class);

    /** Path prefixes that are legitimately not tenant-scoped. See the class note. */
    private static final Set<String> UNSCOPED_PREFIXES = Set.of(
            "/tenancy",
            "/users",
            "/memberships",
            "/invitations",
            "/refresh-tokens",
            "/federated-identities",
            "/subscriptions",
            "/invoices",
            "/billing-webhook-events",
            "/health",
            "/actuator");

    /**
     * Off by default.
     *
     * <p>Enabling this refuses requests that work today, so it must be a deliberate act rather than
     * something that arrives with a deploy. Turn it on once
     * {@code dao.workload.trust.web-experience} is configured and the logs below are quiet — the
     * two go together, because a caller with no signed tenant is exactly what this refuses.
     */
    private final boolean enforce;

    public TenantScopeFilter(@Value("${dao.tenancy.require-scope:false}") boolean enforce) {
        this.enforce = enforce;
        if (!enforce) {
            log.warn("Tenant scoping is NOT enforced. A read with no brandId still returns every "
                    + "tenant's rows. Set dao.tenancy.require-scope=true once callers send a "
                    + "signed tenant.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (requiresTenant(request)
                && CallerTenant.resolve(request.getParameter("brandId")) == null
                && !CallerTenant.mayReadAcrossTenants()) {
            // Logged whether or not it is enforced: while off, these lines are the inventory of
            // what would break, which is what makes turning it on a measured decision instead of
            // a gamble.
            log.warn("Tenant-scoped request with no brand: {} {}{}",
                    request.getMethod(), request.getRequestURI(),
                    enforce ? " — REFUSED" : " — allowed (enforcement off)");

            if (enforce) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(
                        "{\"status\":400,\"error\":\"Bad Request\","
                                + "\"message\":\"This endpoint is tenant-scoped and no brand was "
                                + "supplied or signed\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * Whether this path reads or writes tenant data.
     *
     * <p>Only GET and DELETE without a brand are treated as scoped reads here. A POST or PUT
     * carries its tenant in the body, which this filter deliberately does not parse: consuming the
     * request body in a filter breaks the controller's own binding, and the write path is already
     * covered because the BFF sets {@code brandId} on the payload from the verified token.
     */
    private boolean requiresTenant(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"DELETE".equals(method)) {
            return false;
        }

        String path = request.getRequestURI();
        for (String prefix : UNSCOPED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return false;
            }
        }

        // The webhook store lookup: a provider's own store id selects exactly one connection, and
        // the caller genuinely does not know its brand yet — that lookup is what tells it. Narrow
        // on purpose: it is only exempt when BOTH parameters are present, so it cannot be used to
        // list connections generally.
        if (path.equals("/marketplace-connections")
                && request.getParameter("providerKey") != null
                && request.getParameter("externalAccountRef") != null) {
            return false;
        }

        // A path ending in an id is fetching one row, and the controller checks ownership on it.
        // Requiring a brand here would break every by-id read for no gain — the IDOR being closed
        // is the unfiltered LIST, not the single fetch.
        return !endsWithIdentifier(path);
    }

    /** Whether the last path segment looks like a UUID — i.e. a single-row fetch. */
    private boolean endsWithIdentifier(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == path.length() - 1) {
            return false;
        }
        String last = path.substring(lastSlash + 1);
        return last.length() == 36 && last.charAt(8) == '-' && last.charAt(13) == '-';
    }
}
