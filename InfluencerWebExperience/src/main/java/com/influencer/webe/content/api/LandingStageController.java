package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.LandingStageMachine;
import com.influencer.webe.content.application.LandingStageService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The stage command endpoint (roadmap §4 rule 1, D.3).
 *
 * <p><b>One path for every origin.</b> The builder, the board and any API client all issue the
 * same command, so the transition rules cannot be bypassed by picking a different door. The
 * board in particular does <b>not</b> write {@code workflow_cards.stage_id} directly — it asks
 * content to change the page stage, and the card moves only if content accepts.
 *
 * <p>A refusal is a 409 carrying the reason, which is what lets the UI snap an optimistic drag
 * back and tell the user why.
 */
@RestController
public class LandingStageController {

    private final LandingStageService stageService;
    private final LandingStageMachine machine;
    private final RequestUserResolver requestUserResolver;
    private final DaoGatewayClient dao;

    public LandingStageController(LandingStageService stageService,
                                  LandingStageMachine machine,
                                  RequestUserResolver requestUserResolver,
                                  DaoGatewayClient dao) {
        this.stageService = stageService;
        this.machine = machine;
        this.requestUserResolver = requestUserResolver;
        this.dao = dao;
    }

    /**
     * Change a page's stage. The single write path (D.3).
     *
     * <p>{@code source} records the origin (board | builder | api) so a board-originated change
     * is not echoed back to the board as a second move (rule 4). {@code idempotencyKey} lets a
     * retry be absorbed rather than replayed.
     */
    @PutMapping("/api/landing-pages/{id}/stage")
    public JsonNode changeStage(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable UUID id,
                                @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE);
        String to = payload.path("to").asText(null);
        if (to == null || to.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' stage is required");
        }
        String source = payload.path("source").asText("api");
        if (!java.util.Set.of("board", "builder", "api").contains(source)) {
            // Not cosmetic: an unrecognised source would be stored and could later suppress
            // the wrong echo, so it is rejected rather than coerced.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source must be one of board, builder, api");
        }
        return stageService.changeStage(brandId, id, to, source, payload.path("idempotencyKey").asText(null));
    }

    /** The stage vocabulary and what each stage may move to — so the UI can grey out illegal drops. */
    @GetMapping("/api/landing-pages/stages")
    public JsonNode stages(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        ObjectNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var stages = out.putArray("stages");
        LandingStageMachine.STAGES.forEach(stages::add);
        ObjectNode allowed = out.putObject("allowed");
        for (String stage : LandingStageMachine.STAGES) {
            var targets = allowed.putArray(stage);
            machine.allowedFrom(stage).forEach(targets::add);
        }
        return out;
    }

    /** Transition history for a page — the answer to "why did this card move?". */
    @GetMapping("/api/landing-pages/{id}/transitions")
    public JsonNode transitions(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);

        // Re-check ownership before returning history: the id comes from the caller, and the
        // transition log would otherwise leak another brand's page lifecycle.
        JsonNode template = dao.get("/landing-templates/" + id, null);
        if (template == null || !template.hasNonNull("brandId")
                || !template.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("landingTemplateId", id.toString());
        return dao.get("/stage-transitions", query);
    }
}
