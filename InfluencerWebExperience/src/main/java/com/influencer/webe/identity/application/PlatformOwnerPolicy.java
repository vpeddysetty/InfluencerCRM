package com.influencer.webe.identity.application;

import com.influencer.webe.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who may reach the subscription and billing surface while pricing is undecided.
 *
 * <p><b>Why this exists.</b> The product is being taken to brands and agencies to build a case
 * study before there is a price. Until then a customer must not meet a checkout, a plan picker or
 * an invoice list — not because those are broken, but because offering to charge someone for a
 * product whose price has not been decided is a promise nobody can keep. The whole billing path
 * stays built, tested and deployed; it is simply reachable by one account.
 *
 * <p><b>This is not a role, and deliberately so.</b> §5's six roles are about what someone may do
 * inside a brand they belong to. This is a different question — whether an account belongs to the
 * person who runs the platform — and modelling it as a seventh role would put it in
 * {@code AccountRole}, where {@code impliesAllBrands()} and {@code findAccessibleBrands} would both
 * have to learn about it, in three trees that must stay in step. A property consulted at the edge
 * touches none of that.
 *
 * <p><b>Matched on the VERIFIED email from the JWT</b> ({@link TenantContext#email()}), never on a
 * header or a request body. The same rule as {@code AuthoritativeTenant}: the brand comes from the
 * token, not from {@code X-Brand-Id}, because anything the caller can type is something the caller
 * can choose.
 *
 * <p><b>Empty means nobody</b> — not everybody. An unset property in a deployment that has not been
 * configured must not silently open billing to every account; it closes it, and the operator finds
 * out by being refused rather than by a customer reaching a checkout.
 */
@Service
public class PlatformOwnerPolicy {

    private final Set<String> ownerEmails;

    public PlatformOwnerPolicy(
            // Comma-separated so a second operator can be added without a code change. Compared
            // lower-cased and trimmed: the column is free text and " Vijay@… " is a reachable state.
            @Value("${web-experience.platform-owner-emails:}") String configured) {
        Set<String> parsed = new LinkedHashSet<>();
        if (configured != null && !configured.isBlank()) {
            Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .forEach(parsed::add);
        }
        this.ownerEmails = Set.copyOf(parsed);
    }

    /** Whether this caller runs the platform. */
    public boolean isPlatformOwner(TenantContext context) {
        if (context == null || context.email() == null || context.email().isBlank()) {
            return false;
        }
        return ownerEmails.contains(context.email().trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Refuse a non-owner, as {@code 404}.
     *
     * <p><b>404 rather than 403.</b> A 403 confirms the endpoint exists and says "you specifically
     * may not" — which, on a surface being hidden precisely because it is not on offer yet, is an
     * invitation to ask why and a hint that a billing system is sitting behind it. 404 says the
     * thing is not there, which is what a customer should understand: there is no plan to buy.
     *
     * <p>The server is the gate. Hiding the nav entry is an affordance and nothing more — §5 is
     * explicit that {@code permission} in the route manifest hides a link and the server re-checks
     * every action. This is that re-check.
     */
    public void requirePlatformOwner(TenantContext context) {
        if (!isPlatformOwner(context)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
