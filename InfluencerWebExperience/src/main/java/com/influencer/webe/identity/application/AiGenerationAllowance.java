package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * How many billed AI calls an account has left this month (V48).
 *
 * <p><b>A cost ceiling, not a paywall.</b> Every generation and rewrite is a billed Anthropic
 * request and nothing counted them, so one account retrying in a loop could run up spend without
 * limit. The free allowance is deliberately generous — more than authoring a campaign in good faith
 * takes — because the blank canvas is the problem this feature exists to remove, and metering it
 * into uselessness would trade the activation the product needs for revenue it does not yet have.
 *
 * <p><b>Separate from {@link EntitlementService} on purpose.</b> That class answers "how many of
 * these may exist", counted from rows that persist. This answers "how many may be done this month",
 * counted from events that expire. Folding a resetting allowance into an enum of capacities would
 * mean one of the two reads wrongly at a glance.
 */
@Service
public class AiGenerationAllowance {

    private static final Logger LOG = LoggerFactory.getLogger(AiGenerationAllowance.class);

    private final DaoGatewayClient dao;
    /** Reused rather than reimplemented: it already fails closed on the free tier. */
    private final EntitlementService entitlements;

    public AiGenerationAllowance(DaoGatewayClient dao, EntitlementService entitlements) {
        this.dao = dao;
        this.entitlements = entitlements;
    }

    /**
     * Refuses with 402 if this account has used its month's allowance.
     *
     * <p>Checked BEFORE the model is called, not after: the point is to avoid the billed request,
     * and a check that runs afterwards has already spent the money it exists to save.
     */
    public void require(UUID accountId) {
        if (accountId == null) {
            // No account resolved means no allowance to enforce. Deliberately permissive: the
            // alternative is refusing a generation because tenancy could not be read, which turns
            // an internal problem into the user's problem on a feature that is supposed to help.
            return;
        }
        PlanPolicy plan = entitlements.planFor(accountId);
        long used = usedThisMonth(accountId);
        if (plan.allowsAiGeneration(used)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                "You have used all " + plan.maxAiGenerationsPerMonth()
                        + " AI drafts on the " + plan.key() + " plan this month. They reset on the "
                        + "1st — or upgrade for more. You can still write and edit pages by hand.");
    }

    /**
     * Records a call that was actually made.
     *
     * <p><b>Never throws.</b> A failure here means the count is low by one, which risks a little
     * spend; a failure that propagated would throw away a generation the user is waiting for and
     * that has already been billed. The cheaper mistake is the one that keeps the work.
     */
    public void record(UUID accountId, UUID brandId, String kind, String generator) {
        if (accountId == null) {
            return;
        }
        try {
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("accountId", accountId.toString());
            if (brandId != null) {
                body.put("brandId", brandId.toString());
            }
            body.put("kind", kind);
            body.put("generator", generator == null || generator.isBlank() ? "anthropic" : generator);
            dao.post("/ai-generation-usage", body);
        } catch (RuntimeException e) {
            LOG.warn("[ai-allowance] could not record a {} for account {}: {}",
                    kind, accountId, e.toString());
        }
    }

    /** What the account has used and what it may use, for the UI to show before anyone is refused. */
    public Map<String, Object> summary(UUID accountId) {
        if (accountId == null) {
            return Map.of("used", 0, "limit", PlanPolicy.UNLIMITED);
        }
        PlanPolicy plan = entitlements.planFor(accountId);
        return Map.of(
                "used", usedThisMonth(accountId),
                "limit", plan.maxAiGenerationsPerMonth(),
                "resetsAt", startOfNextMonth().toString());
    }

    private long usedThisMonth(UUID accountId) {
        try {
            JsonNode row = dao.get("/ai-generation-usage",
                    Map.of("accountId", accountId.toString(), "since", startOfMonth().toString()));
            return row == null ? 0 : row.path("used").asLong(0);
        } catch (RuntimeException e) {
            // Reading the count failed. ALLOW the generation rather than refusing it: this is a
            // spend ceiling, and one uncounted call costs a fraction of a cent, whereas refusing
            // everyone because the DAO hiccuped breaks the feature for every account at once.
            LOG.warn("[ai-allowance] could not read usage for account {}, allowing: {}",
                    accountId, e.toString());
            return 0;
        }
    }

    /**
     * The first instant of the current month, UTC.
     *
     * <p>UTC and not the user's zone: the allowance is a billing construct, the spend it caps is
     * billed in UTC, and a per-user boundary would let the same account reset twice by travelling.
     */
    private Instant startOfMonth() {
        return ZonedDateTime.now(ZoneOffset.UTC)
                .withDayOfMonth(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .toInstant();
    }

    private Instant startOfNextMonth() {
        return ZonedDateTime.ofInstant(startOfMonth(), ZoneOffset.UTC).plusMonths(1).toInstant();
    }
}
