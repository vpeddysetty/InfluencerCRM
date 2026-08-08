package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

    public EntitlementService(DaoGatewayClient dao) {
        this.dao = dao;
    }

    /** The plan currently on an account. Falls back to {@link PlanPolicy#FREE}, never to unlimited. */
    public PlanPolicy planFor(UUID accountId) {
        if (accountId == null) {
            return PlanPolicy.FREE;
        }
        try {
            JsonNode account = dao.get("/tenancy/accounts/" + accountId, null);
            return PlanPolicy.forKey(account == null ? null : account.path("plan").asText(null));
        } catch (RuntimeException e) {
            // Fail closed on the free tier rather than open. An unreachable DAO must not become a
            // way to bypass limits — but it must also not lock out an account that is within its
            // limits anyway, which the free tier still allows.
            log.warn("Could not read the plan for account {}, assuming free: {}", accountId, e.toString());
            return PlanPolicy.FREE;
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
