package com.influencer.webe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.client.DaoGatewayClient;
import com.influencer.webe.service.RequestUserResolver;
import com.influencer.webe.service.ResponseShapeService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WorkflowController {
    private final DaoGatewayClient daoGatewayClient;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService responseShapeService;

    public WorkflowController(DaoGatewayClient daoGatewayClient,
                              RequestUserResolver requestUserResolver,
                              ResponseShapeService responseShapeService) {
        this.daoGatewayClient = daoGatewayClient;
        this.requestUserResolver = requestUserResolver;
        this.responseShapeService = responseShapeService;
    }

    @GetMapping("/creator-workflow-tasks")
    public JsonNode listTasks(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestParam(required = false) UUID userId,
                              @RequestParam(required = false) UUID campaignCreatorId,
                              @RequestParam(required = false) String taskType,
                              @RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        query.put("campaignCreatorId", campaignCreatorId == null ? null : campaignCreatorId.toString());
        query.put("taskType", taskType);
        return responseShapeService.workflowTasksList(daoGatewayClient.get("/creator-workflow-tasks", query), page, size);
    }

    @GetMapping("/creator-workflow-tasks/{id}")
    public JsonNode taskById(@PathVariable UUID id) { return responseShapeService.workflowTask(daoGatewayClient.get("/creator-workflow-tasks/" + id, null)); }

    @PostMapping("/creator-workflow-tasks")
    public JsonNode createTask(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.workflowTask(daoGatewayClient.post("/creator-workflow-tasks", payload));
    }

    @PutMapping("/creator-workflow-tasks/{id}")
    public JsonNode updateTask(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable UUID id,
                               @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.workflowTask(daoGatewayClient.put("/creator-workflow-tasks/" + id, payload));
    }

    @DeleteMapping("/creator-workflow-tasks/{id}")
    public void deleteTask(@PathVariable UUID id) { daoGatewayClient.delete("/creator-workflow-tasks/" + id); }

    @GetMapping("/creator-workflow-approvals")
    public JsonNode listApprovals(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestParam(required = false) UUID userId,
                                  @RequestParam(required = false) UUID campaignCreatorId,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        query.put("campaignCreatorId", campaignCreatorId == null ? null : campaignCreatorId.toString());
        return responseShapeService.workflowApprovalsList(daoGatewayClient.get("/creator-workflow-approvals", query), page, size);
    }

    @GetMapping("/creator-workflow-approvals/{id}")
    public JsonNode approvalById(@PathVariable UUID id) { return responseShapeService.workflowApproval(daoGatewayClient.get("/creator-workflow-approvals/" + id, null)); }

    @PostMapping("/creator-workflow-approvals")
    public JsonNode createApproval(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.workflowApproval(daoGatewayClient.post("/creator-workflow-approvals", payload));
    }

    @PutMapping("/creator-workflow-approvals/{id}")
    public JsonNode updateApproval(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable UUID id,
                                   @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.workflowApproval(daoGatewayClient.put("/creator-workflow-approvals/" + id, payload));
    }

    @DeleteMapping("/creator-workflow-approvals/{id}")
    public void deleteApproval(@PathVariable UUID id) { daoGatewayClient.delete("/creator-workflow-approvals/" + id); }

    @GetMapping("/creator-workflow-payments")
    public JsonNode listPayments(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestParam(required = false) UUID userId,
                                 @RequestParam(required = false) UUID campaignCreatorId,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        query.put("campaignCreatorId", campaignCreatorId == null ? null : campaignCreatorId.toString());
        return responseShapeService.workflowPaymentsList(daoGatewayClient.get("/creator-workflow-payments", query), page, size);
    }

    @GetMapping("/creator-workflow-payments/{id}")
    public JsonNode paymentById(@PathVariable UUID id) { return responseShapeService.workflowPayment(daoGatewayClient.get("/creator-workflow-payments/" + id, null)); }

    @PostMapping("/creator-workflow-payments")
    public JsonNode createPayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.workflowPayment(daoGatewayClient.post("/creator-workflow-payments", payload));
    }

    @PutMapping("/creator-workflow-payments/{id}")
    public JsonNode updatePayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @PathVariable UUID id,
                                  @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.workflowPayment(daoGatewayClient.put("/creator-workflow-payments/" + id, payload));
    }

    @DeleteMapping("/creator-workflow-payments/{id}")
    public void deletePayment(@PathVariable UUID id) { daoGatewayClient.delete("/creator-workflow-payments/" + id); }

    @GetMapping("/creator-workflow-events")
    public JsonNode listEvents(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestParam(required = false) UUID userId,
                               @RequestParam(required = false) UUID campaignCreatorId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        query.put("campaignCreatorId", campaignCreatorId == null ? null : campaignCreatorId.toString());
        return responseShapeService.workflowEventsList(daoGatewayClient.get("/creator-workflow-events", query), page, size);
    }

    @GetMapping("/creator-workflow-events/{id}")
    public JsonNode eventById(@PathVariable UUID id) { return responseShapeService.workflowEvent(daoGatewayClient.get("/creator-workflow-events/" + id, null)); }

    @PostMapping("/creator-workflow-events")
    public JsonNode createEvent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.workflowEvent(daoGatewayClient.post("/creator-workflow-events", payload));
    }

    @PutMapping("/creator-workflow-events/{id}")
    public JsonNode updateEvent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable UUID id,
                                @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.workflowEvent(daoGatewayClient.put("/creator-workflow-events/" + id, payload));
    }

    @DeleteMapping("/creator-workflow-events/{id}")
    public void deleteEvent(@PathVariable UUID id) { daoGatewayClient.delete("/creator-workflow-events/" + id); }

    @GetMapping("/campaign-type-workflow-stages")
    public JsonNode listCampaignTypeWorkflowStages(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestParam(required = false) UUID userId,
                                                   @RequestParam(required = false) String campaignType,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        query.put("campaignType", campaignType);
        return responseShapeService.campaignTypeWorkflowStagesList(daoGatewayClient.get("/campaign-type-workflow-stages", query), page, size);
    }

    @PutMapping("/campaign-type-workflow-stages/replace")
    public JsonNode replaceCampaignTypeWorkflowStages(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.campaignTypeWorkflowStagesList(daoGatewayClient.put("/campaign-type-workflow-stages/replace", payload), null, null);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
