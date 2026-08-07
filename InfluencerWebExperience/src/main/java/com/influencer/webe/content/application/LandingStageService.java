package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    public LandingStageService(DaoGatewayClient dao,
                               ResponseShapeService shape,
                               LandingStageMachine machine,
                               BrandDomainService domains) {
        this.dao = dao;
        this.shape = shape;
        this.machine = machine;
        this.domains = domains;
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

    /**
     * Rule 3: a page cannot be published if there is nothing to serve.
     *
     * <p>Without this a card could be dragged to Published, the stage would change, and the
     * public URL would render an empty page — the board would be reporting a state the page
     * does not have, which is the exact divergence Phase D exists to prevent.
     */
    private void requirePublishable(JsonNode template) {
        // Both fields arrive as JSON *strings* from the DAO (the entity maps jsonb as String),
        // so they must be parsed before being judged. Measuring the raw node's length instead
        // treats the four characters of "\"[]\"" as content and lets an empty page publish.
        boolean hasDocument = hasContent(template.get("document"), false);
        boolean hasBlocks = hasContent(template.get("blocks"), true);
        if (!hasDocument && !hasBlocks) {
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
