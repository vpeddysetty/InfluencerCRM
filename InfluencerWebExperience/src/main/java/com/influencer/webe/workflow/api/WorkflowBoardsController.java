package com.influencer.webe.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.workflow.infrastructure.WorkflowGatewayClient;
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

    public WorkflowBoardsController(WorkflowGatewayClient daoGatewayClient,
                                    RequestUserResolver requestUserResolver,
                                    ResponseShapeService responseShapeService) {
        this.daoGatewayClient = daoGatewayClient;
        this.requestUserResolver = requestUserResolver;
        this.responseShapeService = responseShapeService;
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

    @PutMapping("/workflow-cards/{id}/placement")
    public JsonNode placeCard(@PathVariable UUID id, @RequestBody ObjectNode payload) {
        return responseShapeService.workflowCard(daoGatewayClient.put("/workflow-cards/" + id + "/placement", payload));
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
