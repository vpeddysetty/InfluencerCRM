package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a brand's vetting rules and records every decision (roadmap C2).
 *
 * <p><b>Rules reject and advance; only a human approves.</b> {@link #evaluate} can write
 * {@code rejected} or {@code under_review}. {@link #decide} is the only path to
 * {@code approved}, and it requires a user id. That separation is the roadmap's decision #5
 * expressed as two methods rather than one method with a flag.
 *
 * <p><b>Every decision writes a vetting event.</b> That is what makes "why was I rejected?"
 * answerable — to the creator, to the brand, and to a regulator. Automated rejection without an
 * audit trail is how a platform ends up unable to explain itself.
 */
@Service
public class VettingService {
    private static final Logger log = LoggerFactory.getLogger(VettingService.class);

    private static final Set<String> STATUSES =
            Set.of("lead", "pending", "under_review", "approved", "rejected");

    /** Statuses a human may set directly. `lead` is an entry state, not a decision. */
    private static final Set<String> DECIDABLE =
            Set.of("pending", "under_review", "approved", "rejected");

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final VettingRuleEngine engine;

    public VettingService(DaoGatewayClient dao, ResponseShapeService shape, VettingRuleEngine engine) {
        this.dao = dao;
        this.shape = shape;
        this.engine = engine;
    }

    // ---- rules CRUD ------------------------------------------------------

    public JsonNode listRules(UUID brandId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        JsonNode rules = dao.get("/vetting-rules", q);
        return rules == null ? shape.objectMapper().createArrayNode() : rules;
    }

    public JsonNode saveRule(UUID brandId, ObjectNode payload) {
        String action = payload.path("action").asText("review").toLowerCase(Locale.ROOT);
        if (!Set.of("reject", "review").contains(action)) {
            // The explicit refusal matters more than the validation. Someone will eventually try
            // to add "approve", and they should get a message explaining why rather than a
            // constraint violation from three layers down.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A rule's action must be 'reject' or 'review'. Rules cannot approve a creator — "
                            + "approval grants access to briefs, assets and payment, and is always a human decision.");
        }
        if (payload.get("condition") == null || !payload.get("condition").isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A rule needs a condition of the form { attribute, operator, value }");
        }

        ObjectNode body = payload.deepCopy();
        body.put("brandId", brandId.toString());
        body.put("action", action);
        // Conditions are jsonb-as-String on the entity, the same trap as blocks/document.
        try {
            body.put("condition", shape.objectMapper().writeValueAsString(payload.get("condition")));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable condition");
        }
        return dao.post("/vetting-rules", body);
    }

    public void deleteRule(UUID brandId, UUID ruleId) {
        JsonNode rule = dao.get("/vetting-rules/" + ruleId, null);
        if (rule == null || !rule.hasNonNull("brandId")
                || !rule.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
        }
        dao.delete("/vetting-rules/" + ruleId);
    }

    // ---- evaluation (C2.3) ----------------------------------------------

    /**
     * Run this brand's rules against one creator and apply the outcome.
     *
     * <p>Called on lead creation and on metric refresh. Never produces {@code approved}.
     *
     * @param trigger what caused this evaluation, recorded on the event
     */
    public JsonNode evaluate(UUID brandId, UUID creatorId, String trigger) {
        JsonNode creator = requireCreator(brandId, creatorId);
        JsonNode rules = listRules(brandId);
        VettingRuleEngine.Outcome outcome = engine.evaluate(creator, rules);

        String from = creator.path("vettingStatus").asText("lead");
        if (from.equals(outcome.status())) {
            // Nothing changed. Writing an event anyway would fill the audit trail with noise
            // and make a real decision harder to find.
            return shape.creator(creator);
        }

        // A human decision is never overwritten by a later rule run. A brand that approved
        // someone should not find them auto-rejected when their follower count dips at the next
        // metric refresh — that is Phase C3's alert, not a vetting reversal.
        if ("approved".equals(from) || creator.hasNonNull("vettingDecidedByUserId")) {
            log.info("Skipping rule evaluation for creator {}: a human decision stands", creatorId);
            return shape.creator(creator);
        }

        return applyStatus(brandId, creator, outcome.status(), outcome.ruleId(), outcome.ruleName(),
                outcome.reason(), null, trigger);
    }

    /** Dry-run a draft condition against this brand's existing creators (C2.4). */
    public JsonNode dryRun(UUID brandId, JsonNode condition) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        JsonNode creators = dao.get("/creators", q);

        List<String> matched = engine.dryRun(creators, condition);
        int total = creators != null && creators.isArray() ? creators.size() : 0;

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("totalCreators", total);
        out.put("matched", matched.size());
        // The percentage is the number that actually stops someone: "this would hit 80% of your
        // roster" reads very differently from "this would hit 412 creators".
        out.put("matchedPercent", total == 0 ? 0 : Math.round(matched.size() * 1000.0 / total) / 10.0);
        ArrayNode ids = out.putArray("matchedCreatorIds");
        matched.forEach(ids::add);
        return out;
    }

    // ---- human decisions (C2.6) -----------------------------------------

    /**
     * A human sets a vetting status.
     *
     * <p>The only path to {@code approved}, and it requires a user id — so an approval always
     * has someone's name against it. That is what a brand will be asked to justify.
     */
    public JsonNode decide(UUID brandId, UUID creatorId, UUID userId, String status, String reason) {
        String target = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (!DECIDABLE.contains(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status must be one of " + new java.util.TreeSet<>(DECIDABLE));
        }
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A vetting decision must be attributable to a user");
        }
        JsonNode creator = requireCreator(brandId, creatorId);
        return applyStatus(brandId, creator, target, null, null, reason, userId, "manual");
    }

    /** The review queue: everything a rule did not resolve (C2.6). */
    public JsonNode reviewQueue(UUID brandId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        q.put("vettingStatus", "under_review");
        JsonNode creators = dao.get("/creators", q);
        ArrayNode out = shape.objectMapper().createArrayNode();
        if (creators != null && creators.isArray()) {
            creators.forEach(c -> out.add(shape.creator(c)));
        }
        return out;
    }

    /** The audit trail for one creator — the answer to "why was I rejected?". */
    public JsonNode history(UUID brandId, UUID creatorId) {
        requireCreator(brandId, creatorId);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("creatorId", creatorId.toString());
        JsonNode events = dao.get("/vetting-events", q);
        return events == null ? shape.objectMapper().createArrayNode() : events;
    }

    // ---- quality reports (C2.8) -----------------------------------------

    /**
     * A brand disputes a creator's audience quality.
     *
     * <p>Snapshots what our own signal said AT THE TIME, which is what turns a complaint into a
     * labelled example of the signal being wrong. Without that, "wait for complaints before
     * buying a vendor signal" degrades into someone half-remembering that a few brands grumbled.
     */
    public JsonNode reportQuality(UUID brandId, UUID creatorId, UUID userId, ObjectNode payload) {
        JsonNode creator = requireCreator(brandId, creatorId);

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("creatorId", creatorId.toString());
        if (userId != null) {
            body.put("reportedByUserId", userId.toString());
        }
        body.put("category", payload.path("category").asText("other"));
        body.put("detail", payload.path("detail").asText(null));
        try {
            body.put("signalSnapshot", shape.objectMapper().writeValueAsString(signalSnapshot(creator)));
        } catch (Exception ignored) {
            // A snapshot we cannot serialize is worth less than the report itself; keep the report.
        }
        return dao.post("/creator-quality-reports", body);
    }

    // ---- internals -------------------------------------------------------

    private JsonNode applyStatus(UUID brandId, JsonNode creator, String to, String ruleId,
                                 String ruleName, String reason, UUID userId, String trigger) {
        String from = creator.path("vettingStatus").asText("lead");
        String creatorId = creator.get("id").asText();

        ObjectNode update = creator.deepCopy();
        update.put("vettingStatus", to);
        update.put("vettingDecidedAt", Instant.now().toString());
        if (userId != null) {
            update.put("vettingDecidedByUserId", userId.toString());
        }
        // audience_demographics is jsonb-as-String on the entity; a PUT carrying it as an object
        // fails deserialization. Same trap as blocks/document.
        stringifyJsonb(update, "audienceDemographics", "customAttributes");
        JsonNode saved = dao.put("/creators/" + creatorId, update);

        recordEvent(brandId, creatorId, from, to, ruleId, ruleName, reason, userId, trigger, creator);
        return shape.creator(saved);
    }

    private void recordEvent(UUID brandId, String creatorId, String from, String to,
                             String ruleId, String ruleName, String reason, UUID userId,
                             String trigger, JsonNode creator) {
        try {
            ObjectNode event = shape.objectMapper().createObjectNode();
            event.put("brandId", brandId.toString());
            event.put("creatorId", creatorId);
            event.put("fromStatus", from);
            event.put("toStatus", to);
            if (ruleId != null) event.put("ruleId", ruleId);
            if (ruleName != null) event.put("ruleName", ruleName);
            if (userId != null) event.put("decidedByUserId", userId.toString());
            event.put("reason", reason);
            event.put("snapshot", shape.objectMapper().writeValueAsString(decisionSnapshot(creator, trigger)));
            dao.post("/vetting-events", event);
        } catch (RuntimeException | java.io.IOException e) {
            // Logged loudly rather than swallowed: the audit trail is the point of C2.5, and a
            // decision without its event is exactly the state that leaves a rejection
            // unexplainable later.
            log.warn("Vetting event NOT recorded for creator {} ({} -> {}): {}",
                    creatorId, from, to, e.toString());
        }
    }

    /** What the creator looked like when the decision was taken. */
    private ObjectNode decisionSnapshot(JsonNode creator, String trigger) {
        ObjectNode snapshot = shape.objectMapper().createObjectNode();
        snapshot.put("trigger", trigger);
        for (String field : new String[]{"followerCount", "engagementRate", "averageViews",
                                         "niche", "metricsSource", "metricsFetchedAt",
                                         "classificationSource"}) {
            if (creator.hasNonNull(field)) {
                snapshot.set(field, creator.get(field));
            }
        }
        if (creator.hasNonNull("riskFlags")) {
            snapshot.set("riskFlags", creator.get("riskFlags"));
        }
        return snapshot;
    }

    /** What our own quality signal said, for a dispute (C2.8). */
    private ObjectNode signalSnapshot(JsonNode creator) {
        ObjectNode snapshot = shape.objectMapper().createObjectNode();
        for (String field : new String[]{"followerCount", "engagementRate", "averageViews",
                                         "brandSafetyScore", "metricsSource", "metricsFetchedAt",
                                         "vettingStatus"}) {
            if (creator.hasNonNull(field)) {
                snapshot.set(field, creator.get(field));
            }
        }
        return snapshot;
    }

    private void stringifyJsonb(ObjectNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && (value.isObject() || value.isArray())) {
                try {
                    node.put(field, shape.objectMapper().writeValueAsString(value));
                } catch (Exception ignored) {
                    node.remove(field);
                }
            }
        }
    }

    private JsonNode requireCreator(UUID brandId, UUID creatorId) {
        JsonNode creator = dao.get("/creators/" + creatorId, null);
        if (creator == null || !creator.hasNonNull("brandId")
                || !creator.get("brandId").asText().equals(brandId.toString())) {
            // 404 not 403: another brand's creator should be indistinguishable from one that
            // does not exist.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator not found");
        }
        return creator;
    }
}
