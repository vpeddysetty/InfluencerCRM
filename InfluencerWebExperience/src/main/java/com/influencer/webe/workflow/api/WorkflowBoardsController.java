package com.influencer.webe.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.workflow.infrastructure.WorkflowGatewayClient;
import com.influencer.webe.content.application.LandingStageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WorkflowBoardsController {
    private final WorkflowGatewayClient daoGatewayClient;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService responseShapeService;
    /**
     * Content owns a landing page's stage (§4 rule 1), so a board drag on a page-tracking card
     * goes through content's command rather than writing the card's stage directly.
     */
    private final LandingStageService landingStageService;

    public WorkflowBoardsController(WorkflowGatewayClient daoGatewayClient,
                                    RequestUserResolver requestUserResolver,
                                    ResponseShapeService responseShapeService,
                                    LandingStageService landingStageService) {
        this.daoGatewayClient = daoGatewayClient;
        this.requestUserResolver = requestUserResolver;
        this.responseShapeService = responseShapeService;
        this.landingStageService = landingStageService;
    }

    // ---- boards --------------------------------------------------------
    @GetMapping("/workflow-boards")
    public JsonNode listBoards(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestParam(required = false) UUID brandId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolvedBrandId.toString());
        return responseShapeService.workflowBoardsList(daoGatewayClient.get("/workflow-boards", query), page, size);
    }

    @GetMapping("/workflow-boards/{id}")
    public JsonNode boardById(@PathVariable UUID id) {
        return responseShapeService.workflowBoard(daoGatewayClient.get("/workflow-boards/" + id, null));
    }

    @PostMapping("/workflow-boards")
    public JsonNode createBoard(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_WRITE).toString());
        return responseShapeService.workflowBoard(daoGatewayClient.post("/workflow-boards", payload));
    }

    @PutMapping("/workflow-boards/{id}")
    public JsonNode updateBoard(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable UUID id,
                                @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_WRITE).toString());
        return responseShapeService.workflowBoard(daoGatewayClient.put("/workflow-boards/" + id, payload));
    }

    @DeleteMapping("/workflow-boards/{id}")
    public void deleteBoard(@PathVariable UUID id) {
        daoGatewayClient.delete("/workflow-boards/" + id);
    }

    // ---- board stages --------------------------------------------------
    @GetMapping("/workflow-board-stages")
    public JsonNode listStages(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestParam(required = false) UUID brandId,
                               @RequestParam(required = false) UUID boardId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolvedBrandId.toString());
        query.put("boardId", boardId == null ? null : boardId.toString());
        return responseShapeService.workflowBoardStagesList(daoGatewayClient.get("/workflow-board-stages", query), page, size);
    }

    @PutMapping("/workflow-board-stages/replace")
    public JsonNode replaceStages(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_WRITE).toString());
        return responseShapeService.workflowBoardStagesList(daoGatewayClient.put("/workflow-board-stages/replace", payload), null, null);
    }

    // ---- stage mappings (Phase D.6) -------------------------------------

    /**
     * Page stage -> board stage mapping for this brand's boards.
     *
     * Per brand rather than a platform default: an agency running luxury beauty and one
     * running gaming genuinely arrange their boards differently, and hard-coding either would
     * be wrong for the other.
     */
    @GetMapping("/stage-mappings")
    public JsonNode listStageMappings(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestParam(required = false) UUID boardId) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", brandId.toString());
        query.put("boardId", boardId == null ? null : boardId.toString());
        return daoGatewayClient.get("/stage-mappings", query);
    }

    /** Upsert one page-stage -> board-stage mapping. */
    @PostMapping("/stage-mappings")
    public JsonNode saveStageMapping(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_WRITE);
        // Stamped from the verified token, never taken from the body — otherwise a caller
        // could write a mapping onto another brand's board.
        payload.put("brandId", brandId.toString());
        return daoGatewayClient.post("/stage-mappings", payload);
    }

    // ---- workflow cards (campaign<->creator relationship tasks) ----------
    @GetMapping("/workflow-cards")
    public JsonNode listCards(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestParam(required = false) UUID brandId,
                              @RequestParam(required = false) UUID boardId,
                              @RequestParam(required = false) String board,
                              @RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer size) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolvedBrandId.toString());
        query.put("boardId", boardId == null ? null : boardId.toString());
        query.put("board", board);
        return responseShapeService.workflowCardsList(daoGatewayClient.get("/workflow-cards", query), page, size);
    }

    @GetMapping("/workflow-cards/{id}")
    public JsonNode cardById(@PathVariable UUID id) {
        return responseShapeService.workflowCard(daoGatewayClient.get("/workflow-cards/" + id, null));
    }

    @PostMapping("/workflow-cards")
    public JsonNode createCard(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_WRITE).toString());
        return responseShapeService.workflowCard(daoGatewayClient.post("/workflow-cards", payload));
    }

    @PutMapping("/workflow-cards/{id}")
    public JsonNode updateCard(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable UUID id,
                               @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_WRITE).toString());
        return responseShapeService.workflowCard(daoGatewayClient.put("/workflow-cards/" + id, payload));
    }

    /**
     * Place a card on a board stage (drag-and-drop).
     *
     * <p><b>Phase D, §4 rule 1.</b> When the card tracks a landing page, a drag is no longer a
     * direct write: it becomes a <i>command</i> that the content context validates and may
     * refuse. Content owns the page's stage, so the card only ever moves in response to an
     * accepted transition — which is what stops the board holding a stage the page does not
     * have. A refused drag returns 409 with a reason and the UI snaps the card back.
     *
     * <p>A card with no landing page (most cards — they are campaign/creator tasks) keeps the
     * original direct placement. Nothing owns their stage but the board itself.
     *
     * <p>This also closes a pre-existing hole: the endpoint previously performed no
     * authorization at all, so any caller could place any card by id.
     */
    @PutMapping("/workflow-cards/{id}/placement")
    public JsonNode placeCard(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable UUID id,
                              @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.WORKFLOW_WRITE);

        JsonNode card = daoGatewayClient.get("/workflow-cards/" + id, null);
        if (card == null || !card.hasNonNull("brandId")
                || !card.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found");
        }

        UUID landingTemplateId = card.hasNonNull("landingTemplateId")
                ? UUID.fromString(card.get("landingTemplateId").asText()) : null;

        if (landingTemplateId != null && payload.hasNonNull("stageId")) {
            String pageStage = pageStageForBoardStage(brandId, payload.get("stageId").asText());
            if (pageStage != null) {
                // Issue the command. A refusal propagates as a 409 and the card does NOT move.
                landingStageService.changeStage(brandId, landingTemplateId, pageStage, "board", null);
            }
            // No mapping for that column means it does not correspond to a page stage, so the
            // card may sit there freely — fall through to a plain placement.
        }

        return responseShapeService.workflowCard(
                daoGatewayClient.put("/workflow-cards/" + id + "/placement", payload));
    }

    /** Reverse lookup: which page stage does this board stage represent, if any? */
    private String pageStageForBoardStage(UUID brandId, String stageId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", brandId.toString());
        JsonNode mappings = daoGatewayClient.get("/stage-mappings", query);
        if (mappings == null || !mappings.isArray()) {
            return null;
        }
        for (JsonNode mapping : mappings) {
            if (mapping.hasNonNull("stageId") && mapping.get("stageId").asText().equals(stageId)) {
                return mapping.path("pageStage").asText(null);
            }
        }
        return null;
    }

    @DeleteMapping("/workflow-cards/{id}")
    public void deleteCard(@PathVariable UUID id) {
        daoGatewayClient.delete("/workflow-cards/" + id);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
