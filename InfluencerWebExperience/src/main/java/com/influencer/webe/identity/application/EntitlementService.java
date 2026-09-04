package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Enforces plan limits at the point of creation (roadmap M2.3).
 *
 * <p><b>Reads the plan live, never from the JWT.</b> This is the one design decision in M2.3 that
 * is hard to reverse. Access tokens already carry {@code acc}, {@code role} and {@code perms}, and
 * adding {@code plan} alongside them would be the obvious move — but a plan in a token is a plan
 * frozen at issue time. A customer who upgrades stays blocked until their token expires, which is
 * the single worst moment in the product to serve a stale answer. One extra DAO read per creation
 * is cheap; creation is not a hot path, and reads are not metered.
 *
 * <p><b>Blocks new, never touches existing.</b> A downgrade — or a limit introduced above accounts
 * that already exceed it — freezes creation and nothing else. No record is deleted, hidden, or made
 * unreadable. Hiding data a customer already has is not a monetization lever; it is data loss they
 * did not consent to, and it is not reversible from their side.
 *
 * <p><b>402, not 403.</b> A limit is not an authorization failure — the caller has every right to
 * do this, their plan simply does not include it. 403 would tell a UI to hide the action; 402 tells
 * it to offer an upgrade, which is the actual remedy.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    private final DaoGatewayClient dao;
    private final PlanPolicy defaultPlan;

    @Autowired
    public EntitlementService(DaoGatewayClient dao,
                              // The plan an account gets when it has none of its own.
                              //
                              // WHY THIS IS A PROPERTY AND NOT A CONSTANT. The product is being taken
                              // to brands and agencies to build a case study BEFORE pricing exists.
                              // A prospect evaluating it must not hit a 25-creator wall with no
                              // pricing page to explain it and no way to upgrade -- that is a worse
                              // first impression than any price would be. So the default is `agency`
                              // for now.
                              //
                              // Every limit check, every 402 path and all of the Stripe billing stay
                              // exactly as built. NOTHING here is deleted, because the enforcement is
                              // the part that is expensive to get right and it is already right. When
                              // pricing is decided, set this back to `free` and the tiers resume --
                              // one property, no code change, no migration.
                              //
                              // An account with an explicit plan is unaffected: this is consulted
                              // only when the column is null, blank or unrecognised.
                              @Value("${web-experience.plans.default:free}") String defaultPlanKey) {
        this.dao = dao;
        this.defaultPlan = PlanPolicy.forKey(defaultPlanKey);
    }

    /**
     * The pre-configuration behaviour: default to {@code FREE}.
     *
     * <p><b>The constructor above carries {@code @Autowired} because this one exists.</b> With two
     * constructors and no annotation Spring looks for a no-arg one, finds neither, and the whole
     * BFF fails to start -- which is exactly what happened on the v1.0.58 roll: every container
     * came up, the API was down for ten minutes, and the instance refresh still reported Successful.
     * Do not remove the annotation while this overload exists.
     *
     * <p>Kept so the many tests that construct this directly keep asserting the SHIPPED default
     * rather than whichever value a deployment happens to carry. A test that silently inherited
     * `agency` would stop testing the limits it was written to test, and would go green for the
     * wrong reason the day the property changes.
     */
    EntitlementService(DaoGatewayClient dao) {
        this(dao, PlanPolicy.FREE.key());
    }

    /**
     * The plan currently on an account.
     *
     * <p>Falls back to the configured default (see the constructor), not to unlimited by accident.
     * {@link PlanPolicy#forKey} itself still fails closed to {@code FREE} for an unrecognised
     * string -- that behaviour is untouched, because a typo in a stored plan must never become a
     * grant of everything. What is configurable is the deliberate default, which is a different
     * thing from a typo.
     */
    public PlanPolicy planFor(UUID accountId) {
        if (accountId == null) {
            return defaultPlan;
        }
        try {
            JsonNode account = dao.get("/tenancy/accounts/" + accountId, null);
            String stored = account == null ? null : account.path("plan").asText(null);
            if (stored == null || stored.isBlank()) {
                return defaultPlan;
            }
            return PlanPolicy.forKey(stored);
        } catch (RuntimeException e) {
            // An unreachable DAO must not become a way to bypass limits, nor a way to lock out an
            // account that is within its limits anyway. The configured default is the honest answer
            // to "we could not ask": it is what an account with no plan would get regardless.
            log.warn("Could not read the plan for account {}, assuming the default: {}", accountId, e.toString());
            return defaultPlan;
        }
    }

    /**
     * Refuses the request with 402 if creating one more would exceed the plan.
     *
     * @param accountId    the paying entity, from the verified JWT claim — never from a body
     * @param resource     what is being created
     * @param currentCount how many the account already has
     */
    public void requireCapacity(UUID accountId, PlanPolicy.Resource resource, long currentCount) {
        PlanPolicy plan = planFor(accountId);
        if (plan.allows(resource, currentCount)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, message(plan, resource));
    }

    /** Convenience for the common case: count the account's rows, then check. */
    public void requireCapacity(UUID accountId, PlanPolicy.Resource resource) {
        requireCapacity(accountId, resource, currentUsage(accountId, resource));
    }

    /**
     * Refuses the request with 402 if creating {@code additional} more would exceed the plan.
     *
     * <p><b>All-or-nothing on purpose.</b> The alternative — take the first N that fit, drop the
     * rest — is worse in every direction: the admin who uploaded thirty names cannot tell which
     * eight got in without reading a results table line by line, the twenty-two that were dropped
     * were dropped by their position in a file rather than by any decision, and re-uploading to
     * catch the remainder silently re-sends the ones that succeeded. Refusing the whole batch with
     * the numbers stated is the only outcome an admin can act on.
     *
     * <p>Deliberately not {@link #requireCapacity} in a loop: each call would re-read the same
     * count and pass, so a batch of twenty would sail past a single free seat.
     *
     * @param currentCount how many the account already has, including anything already committed
     *                     (pending invitations hold a seat — see the caller)
     * @param additional   how many this request would create
     */
    public void requireCapacityFor(UUID accountId, PlanPolicy.Resource resource,
                                   long currentCount, long additional) {
        PlanPolicy plan = planFor(accountId);
        if (plan.allowsAdditional(resource, currentCount, additional)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                batchMessage(plan, resource, currentCount, additional));
    }

    /**
     * How many more of {@code resource} the plan still allows, or {@link PlanPolicy#UNLIMITED}.
     *
     * <p>Exists so a batch can be sized <em>before</em> it is sent. The 402 above is the backstop;
     * a UI that can say "27 needed, 40 available" never has to reach it.
     */
    public long remainingCapacity(UUID accountId, PlanPolicy.Resource resource, long currentCount) {
        int limit = planFor(accountId).limitFor(resource);
        return limit == PlanPolicy.UNLIMITED ? PlanPolicy.UNLIMITED : Math.max(0, limit - currentCount);
    }

    /**
     * Refuses the request with 402 if the plan does not include role-based access.
     *
     * <p><b>402, not 403.</b> The caller holds {@code MEMBER_UPDATE} and is entirely authorized;
     * their plan simply does not include this. 403 tells a UI to hide the control, 402 tells it to
     * offer the upgrade — and hiding it would leave a free user unable to discover that team roles
     * exist at all.
     *
     * <p>Gates assignment only. Roles already stored keep working, so a downgrade never widens
     * anyone's access — see {@link PlanPolicy#allowsRoleBasedAccess()}.
     */
    public void requireRoleBasedAccess(UUID accountId) {
        PlanPolicy plan = planFor(accountId);
        if (plan.allowsRoleBasedAccess()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                "Team roles are part of Pro. Your free plan is for a single user — "
                + "upgrade to invite teammates and choose what each of them can do. "
                + "Any roles already assigned keep working.");
    }

    /**
     * How many of {@code resource} an account already has.
     *
     * <p>Counted per ACCOUNT, across every brand it owns. Counting per brand would let anyone
     * multiply their allowance by creating brands — which is itself metered, but only up to the
     * brand limit, and on the agency tier not at all.
     */
    public long currentUsage(UUID accountId, PlanPolicy.Resource resource) {
        if (accountId == null) {
            return 0;
        }
        return switch (resource) {
            case BRAND -> count(dao.get("/tenancy/accounts/" + accountId + "/brands", Map.of()));
            case MEMBER -> count(dao.get("/tenancy/accounts/" + accountId + "/members", Map.of()));
            case CREATOR -> countAcrossBrands(accountId, "/creators");
            case LANDING_PAGE -> countAcrossBrands(accountId, "/landing-templates");
            // PR-39. Counted across the account's brands like landing pages, NOT per brand: the
            // limit exists to bound storage on a free tier, and metering per brand would let an
            // agency account multiply its allowance by adding brands — which are themselves
            // unlimited on that tier.
            case SAVED_TEMPLATE -> countAcrossBrands(accountId, "/brand-page-templates");
        };
    }

    /**
     * The message a user actually sees. It names the limit, the plan, and what to do.
     *
     * <p>"Limit reached" alone forces a support ticket to discover which limit and what the next
     * tier allows.
     */
    private String message(PlanPolicy plan, PlanPolicy.Resource resource) {
        int limit = plan.limitFor(resource);
        String next = plan == PlanPolicy.FREE ? "Pro" : "Agency";
        return "Your %s plan includes %d %s. Upgrade to %s to add more — nothing you already have "
                .formatted(plan.key(), limit, resource.plural(), next)
                + "is affected.";
    }

    /**
     * The message for a refused batch. Names what was asked for, what is available, and every way
     * out of it.
     *
     * <p>Says "nothing was sent" explicitly. The immediate fear on a failed bulk action is that it
     * half-succeeded and some unknown subset of people were invited; leaving that unanswered means
     * the admin's next move is to go looking rather than to fix the batch.
     */
    private String batchMessage(PlanPolicy plan, PlanPolicy.Resource resource,
                                long currentCount, long additional) {
        int limit = plan.limitFor(resource);
        long available = Math.max(0, limit - currentCount);
        String next = plan == PlanPolicy.FREE ? "Pro" : "Agency";
        return ("This needs %d %s and your %s plan has %d available (%d of %d already used, "
                + "including pending invitations). Nothing was sent — send %d or fewer, free a seat "
                + "by revoking a pending invitation, or upgrade to %s.")
                .formatted(additional, resource.plural(), plan.key(), available,
                        currentCount, limit, available, next);
    }

    /** Sums a per-brand resource over every brand in the account. */
    private long countAcrossBrands(UUID accountId, String path) {
        JsonNode brands = dao.get("/tenancy/accounts/" + accountId + "/brands", Map.of());
        if (brands == null || !brands.isArray()) {
            return 0;
        }
        long total = 0;
        for (JsonNode brand : brands) {
            String brandId = brand.path("id").asText(null);
            if (brandId == null) {
                continue;
            }
            total += count(dao.get(path, Map.of("brandId", brandId)));
        }
        return total;
    }

    private static long count(JsonNode rows) {
        return rows == null || !rows.isArray() ? 0 : rows.size();
    }
}
