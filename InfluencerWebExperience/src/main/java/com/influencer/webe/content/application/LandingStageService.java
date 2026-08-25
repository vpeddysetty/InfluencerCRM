package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.PlatformMetrics;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;

/**
 * The single command path for changing a landing page's stage (roadmap §4, Phase D).
 *
 * <p><b>Rule 1: content owns the transition, always.</b> A board drag does not write
 * {@code workflow_cards.stage_id} and then tell content. It issues a command that content
 * validates and may refuse. The card only ever moves in response to an accepted transition,
 * so the card can never hold a stage the page does not have.
 *
 * <pre>
 * drag card ──► PUT /api/landing-pages/{id}/stage { to: "published" }
 *                        │
 *             content validates the transition
 *                        │
 *        ┌───────────────┴───────────────┐
 *     accepted                        refused
 *        │                               │
 *  card is moved                    409 + reason
 *                                        │
 *                                 card snaps back
 * </pre>
 *
 * <p><b>Rule 4: source and idempotency.</b> Every transition records where it came from and
 * carries a key. A board-originated change does not echo back as a second move, and a retried
 * or duplicated command is absorbed rather than moving a card twice.
 */
@Service
public class LandingStageService {
    private static final Logger log = LoggerFactory.getLogger(LandingStageService.class);

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final LandingStageMachine machine;
    /** Phase E: reaching Published starts the free-hosting clock (decision #11). */
    private final BrandDomainService domains;
    /** Phase H: a rise in refusals means the map disagrees with how people actually work. */
    private final PlatformMetrics metrics;

    public LandingStageService(DaoGatewayClient dao,
                               ResponseShapeService shape,
                               LandingStageMachine machine,
                               BrandDomainService domains,
                               PlatformMetrics metrics) {
        this.dao = dao;
        this.shape = shape;
        this.machine = machine;
        this.domains = domains;
        this.metrics = metrics;
    }

    /**
     * Change a page's stage.
     *
     * @param source one of board | builder | api — recorded so a board-originated change is
     *               not echoed back to the board as a second move
     * @return the updated page
     */
    public JsonNode changeStage(UUID brandId, UUID templateId, String to, String source, String idempotencyKey) {
        String target = normalize(to);
        if (!machine.isStage(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown stage '" + to + "'. Expected one of " + LandingStageMachine.STAGES);
        }

        JsonNode template = dao.get("/landing-templates/" + templateId, null);
        if (template == null || !template.hasNonNull("brandId")
                || !template.get("brandId").asText().equals(brandId.toString())) {
            // 404 rather than 403: a page belonging to another brand should not be
            // distinguishable from one that does not exist.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }

        String current = normalize(template.path("stage").asText(LandingStageMachine.DRAFT));

        // Rule 2. Refused with 409 and a reason the UI can show when it snaps a card back.
        if (!machine.isAllowed(current, target)) {
            metrics.stageTransition("refused");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot move from '" + current + "' to '" + target + "'. Allowed from '"
                            + current + "': " + machine.allowedFrom(current));
        }

        // Rule 3. Some transitions need more than a stage change, and the check belongs here —
        // before anything moves — precisely so nothing has to be compensated afterwards.
        if (machine.requiresPublishablePage(target) && !current.equals(target)) {
            requirePublishable(template);
        }

        // When the caller supplies a key, it defines what "the same command" means and a retry
        // is absorbed. When it does not, the key must be unique PER OCCURRENCE.
        //
        // An earlier version defaulted to `templateId:from->to`, which looked reasonable and
        // was wrong: work legitimately goes round the loop more than once (draft -> review ->
        // draft -> review), and the second pass collided with the first and was silently
        // dropped. The stage still changed, so the page and board stayed correct — but the
        // transition vanished from the audit trail, which is the one thing the log exists for.
        // Caught by D10 asserting a board-sourced row existed.
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? templateId + ":" + current + "->" + target + ":" + UUID.randomUUID()
                : idempotencyKey;

        // A no-op transition short-circuits: re-sending the current stage is not an error, and
        // must not write a second transition row or move a card again.
        if (current.equals(target)) {
            return shape.landingTemplate(template);
        }

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("campaignId", template.get("campaignId").asText());
        body.put("publicSlug", template.get("publicSlug").asText());
        body.put("name", template.path("name").asText("Landing page"));
        body.put("stage", target);
        // Reaching `published` as a stage also publishes the page: leaving status at draft
        // would mean a page in the Published column that returns 404 to visitors.
        body.put("status", LandingStageMachine.PUBLISHED.equals(target)
                ? "published" : template.path("status").asText("draft"));
        JsonNode updated = dao.put("/landing-templates/" + templateId, body);

        metrics.stageTransition("accepted");
        recordTransition(brandId, templateId, current, target, source, key);
        syncCard(brandId, templateId, target, source, key);

        // Phase E, decision #11: two months of free hosting, measured from FIRST publish rather
        // than signup — a brand that explores for six weeks before publishing should get the
        // full window on the thing being trialled. Idempotent, so republishing later does not
        // restart the clock and make the trial unbounded.
        if (LandingStageMachine.PUBLISHED.equals(target)) {
            try {
                updated = domains.startHostingWindow(brandId, templateId);
            } catch (RuntimeException e) {
                // A page that published but failed to get its expiry stamped hosts indefinitely,
                // which is the safe direction to fail: it costs us, not the customer.
                log.warn("Hosting window NOT started for page {}: {}", templateId, e.toString());
            }
        }

        return shape.landingTemplate(updated);
    }

    // ---- scheduled publish (PR-35) -------------------------------------

    /**
     * Set or move the time this page should publish itself.
     *
     * <p><b>Validated here, not by the scheduler.</b> Refusing an unpublishable page at schedule
     * time tells the user now, while they are looking at the page; discovering it at 9am on launch
     * day means a page that silently did not go live. The same {@code requirePublishable} guard the
     * manual publish uses, applied a few hours earlier.
     *
     * <p><b>A past time is refused</b> rather than quietly publishing on the next sweep. "Publish
     * at 9am" typed after 9am is far more likely to be a wrong date than a request to publish
     * immediately — and the immediate path already exists.
     */
    public JsonNode schedulePublish(UUID brandId, UUID templateId, Instant at) {
        if (at == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A publish time is required");
        }
        JsonNode template = requireOwned(brandId, templateId);

        if (!at.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That publish time has already passed. Pick a future time, or publish now.");
        }
        // Already-published pages have nothing to schedule: the scheduler's transition would be a
        // no-op and the user would be left waiting for something that already happened.
        if (LandingStageMachine.PUBLISHED.equals(normalize(template.path("stage").asText("")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This page is already published");
        }
        requirePublishable(template);

        return writeSchedule(brandId, template, templateId, at);
    }

    /**
     * Clear a pending schedule — used both by a user cancelling and by the scheduler after it
     * publishes. Idempotent: clearing an unscheduled page is not an error, because a retry after a
     * partial failure must be able to finish the job.
     */
    public JsonNode clearSchedule(UUID brandId, UUID templateId) {
        return writeSchedule(brandId, requireOwned(brandId, templateId), templateId, null);
    }

    /**
     * Write the schedule column, preserving everything the projection carries.
     *
     * <p>The DAO PUT replaces the row, so the body must restate the fields that must survive. This
     * mirrors {@code changeStage}'s body rather than sending the whole template back, because the
     * jsonb columns arrive as strings through the projection and round-tripping them here would
     * re-encode them.
     */
    private JsonNode writeSchedule(UUID brandId, JsonNode template, UUID templateId, Instant at) {
        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("campaignId", template.get("campaignId").asText());
        body.put("publicSlug", template.get("publicSlug").asText());
        body.put("name", template.path("name").asText("Landing page"));
        body.put("stage", template.path("stage").asText(LandingStageMachine.DRAFT));
        body.put("status", template.path("status").asText("draft"));
        if (at == null) {
            body.putNull("scheduledPublishAt");
        } else {
            body.put("scheduledPublishAt", at.toString());
        }
        return shape.landingTemplate(dao.put("/landing-templates/" + templateId, body));
    }

    /** The page, or 404 — a page owned by another brand is indistinguishable from a missing one. */
    private JsonNode requireOwned(UUID brandId, UUID templateId) {
        JsonNode template = dao.get("/landing-templates/" + templateId, null);
        if (template == null || !template.hasNonNull("brandId")
                || !template.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        return template;
    }

    /**
     * Rule 3: a page cannot be published if there is nothing to serve.
     *
     * <p>Without this a card could be dragged to Published, the stage would change, and the
     * public URL would render an empty page — the board would be reporting a state the page
     * does not have, which is the exact divergence Phase D exists to prevent.
     */
    /**
     * What the brand should know before publishing this page.
     *
     * <p><b>Advisory, never a refusal.</b> This is deliberately separate from
     * {@link #requirePublishable}: that method decides what is <i>impossible</i> (an empty page),
     * this one reports what is merely <i>unwise</i>. A page with no coupon is a legitimate page —
     * a brand-awareness launch or an announcement was never meant to carry an offer — so blocking
     * it would refuse real work to enforce a preference. The user is told and decides.
     *
     * <p><b>Why it is a GET and not a field on the page.</b> The answer depends on the campaign's
     * coupons, not on the page row, so it would go stale the moment a coupon was added or removed
     * elsewhere. Reading it at the moment of publishing is the only way it is true when shown.
     *
     * @return {@code { trackable, couponCount, warnings: [ { code, message, suggestion } ] }}
     */
    public JsonNode publishReadiness(UUID brandId, UUID templateId) {
        JsonNode template = requireOwnedTemplate(brandId, templateId);

        ObjectNode out = shape.objectMapper().createObjectNode();
        ArrayNode warnings = out.putArray("warnings");

        int couponCount = countCoupons(brandId, template.path("campaignId").asText(null));
        out.put("couponCount", couponCount);
        // "Trackable" here means PURCHASE-trackable. Visits are always countable — the public
        // renderer records a landing_page_view either way — so naming this field `trackable`
        // without that qualification in the message would overstate what is lost.
        out.put("trackable", couponCount > 0);

        if (couponCount == 0) {
            ObjectNode w = warnings.addObject();
            w.put("code", "no_coupon");
            w.put("severity", "warning");
            w.put("message", "This page has no coupon code, so sales made after visiting it "
                    + "cannot be traced back here. Visits are still counted.");
            // The alternative is offered rather than described, because a warning that names no
            // way forward reads as an obstacle. UTM tagging is the honest one: it attributes the
            // CLICK to a campaign and creator without identifying the visitor, which is the only
            // thing available on an anonymous public page with no consent gate.
            w.put("suggestion", "Add a coupon to attribute sales, or publish with campaign "
                    + "tracking on the link to see which creators send the most visits.");
            w.put("canTagLinks", true);
        }

        return out;
    }

    /**
     * How many coupons the campaign has.
     *
     * <p>Best-effort by design: a failure to reach the coupon list must not stop someone
     * publishing. The cost of being wrong is one missing advisory; the cost of throwing is a
     * publish button that breaks because an unrelated service is down.
     */
    private int countCoupons(UUID brandId, String campaignId) {
        if (campaignId == null || campaignId.isBlank()) {
            return 0;
        }
        try {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("brandId", brandId.toString());
            q.put("campaignId", campaignId);
            JsonNode coupons = dao.get("/influencer-campaign-codes", q);
            return coupons != null && coupons.isArray() ? coupons.size() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The page, or 404 — never distinguishing another brand's page from a missing one. */
    private JsonNode requireOwnedTemplate(UUID brandId, UUID templateId) {
        JsonNode template = dao.get("/landing-templates/" + templateId, null);
        if (template == null || !template.hasNonNull("brandId")
                || !template.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        return template;
    }

    private void requirePublishable(JsonNode template) {
        // Both fields arrive as JSON *strings* from the DAO (the entity maps jsonb as String),
        // so they must be parsed before being judged. Measuring the raw node's length instead
        // treats the four characters of "\"[]\"" as content and lets an empty page publish.
        // PR-39. `sections` counts as content, and it has to be checked FIRST for the same
        // reason it wins at render time: a page authored entirely in the section editor has no
        // document and no blocks, and without this it would be refused as empty — the publish
        // button dead-ending on the only editor the brand was given.
        boolean hasSections = hasContent(template.get("sections"), true);
        boolean hasDocument = hasContent(template.get("document"), false);
        boolean hasBlocks = hasContent(template.get("blocks"), true);
        if (!hasSections && !hasDocument && !hasBlocks) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This page has no content yet, so it cannot be published. "
                            + "Add content in the builder first.");
        }
    }

    /**
     * True when a jsonb field holds something worth serving.
     *
     * <p>Accepts either a parsed node or a JSON string, because the DAO returns these fields
     * as text on some hops and as objects on others.
     *
     * @param expectArray true for `blocks` (an array with entries), false for `document`
     *                    (an object whose html or css is non-blank)
     */
    private boolean hasContent(JsonNode field, boolean expectArray) {
        if (field == null || field.isNull()) {
            return false;
        }
        JsonNode parsed = field;
        if (field.isTextual()) {
            try {
                parsed = shape.objectMapper().readTree(field.asText());
            } catch (Exception e) {
                return false;
            }
        }
        if (expectArray) {
            return parsed.isArray() && parsed.size() > 0;
        }
        if (!parsed.isObject()) {
            return false;
        }
        return !parsed.path("html").asText("").isBlank() || !parsed.path("css").asText("").isBlank();
    }

    /** Rule 4: the transition log, keyed so a retry is absorbed rather than replayed. */
    private void recordTransition(UUID brandId, UUID templateId, String from, String to,
                                  String source, String key) {
        try {
            ObjectNode row = shape.objectMapper().createObjectNode();
            row.put("brandId", brandId.toString());
            row.put("landingTemplateId", templateId.toString());
            row.put("fromStage", from);
            row.put("toStage", to);
            row.put("source", source);
            row.put("idempotencyKey", key);
            dao.post("/stage-transitions", row);
        } catch (RuntimeException e) {
            // A duplicate key means this transition was already recorded — the expected outcome
            // of a retry, and precisely what the constraint is for. The stage change itself has
            // already been persisted, so swallowing is correct rather than lossy.
            //
            // Logged rather than ignored: a key collision that is NOT a retry means a
            // transition is missing from the audit trail, and that is exactly how the
            // `from->to` default key hid a lost board-sourced row until a test looked for it.
            log.info("Transition not recorded for page {} ({} -> {}, source={}): key '{}' already exists",
                    templateId, from, to, source, key);
        }
    }

    /**
     * Move the card that tracks this page (D.5).
     *
     * <p>Skipped when the change came FROM the board: the card is already where the user
     * dragged it, and moving it again would be the echo rule 4 exists to prevent.
     *
     * <p>Best-effort. A page's stage is the source of truth and must not fail to change
     * because the board could not be updated; the nightly reconciliation re-emits mismatches.
     */
    private void syncCard(UUID brandId, UUID templateId, String toStage, String source, String key) {
        if ("board".equalsIgnoreCase(source)) {
            return;
        }
        try {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("brandId", brandId.toString());
            q.put("landingTemplateId", templateId.toString());
            JsonNode cards = dao.get("/workflow-cards", q);
            if (cards == null || !cards.isArray() || cards.size() == 0) {
                return;  // no card tracks this page; nothing to move
            }

            for (JsonNode card : cards) {
                UUID boardId = card.hasNonNull("boardId") ? UUID.fromString(card.get("boardId").asText()) : null;
                if (boardId == null) {
                    continue;
                }
                JsonNode mapping = findMapping(brandId, boardId, toStage);
                if (mapping == null || !mapping.hasNonNull("stageId")) {
                    // A brand may deliberately map a page stage to nothing — "this stage has no
                    // place on my board" — so an absent mapping is a valid configuration, not
                    // an error.
                    continue;
                }
                ObjectNode placement = shape.objectMapper().createObjectNode();
                placement.put("boardId", boardId.toString());
                placement.put("stageId", mapping.get("stageId").asText());
                dao.put("/workflow-cards/" + card.get("id").asText() + "/placement", placement);
            }
        } catch (RuntimeException e) {
            // Reconciliation will catch a board left behind. Failing the user's stage change
            // because a card could not be moved would be the wrong trade.
        }
    }

    private JsonNode findMapping(UUID brandId, UUID boardId, String pageStage) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        q.put("boardId", boardId.toString());
        q.put("pageStage", pageStage);
        JsonNode mappings = dao.get("/stage-mappings", q);
        if (mappings == null || !mappings.isArray() || mappings.size() == 0) {
            return null;
        }
        return mappings.get(0);
    }

    private String normalize(String stage) {
        return stage == null ? "" : stage.trim().toLowerCase(Locale.ROOT);
    }
}
