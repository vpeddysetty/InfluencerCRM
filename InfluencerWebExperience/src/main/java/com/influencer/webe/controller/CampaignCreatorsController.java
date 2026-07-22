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
@RequestMapping("/api/campaign-creators")
public class CampaignCreatorsController {
    private final DaoGatewayClient daoGatewayClient;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService responseShapeService;

    public CampaignCreatorsController(DaoGatewayClient daoGatewayClient,
                                      RequestUserResolver requestUserResolver,
                                      ResponseShapeService responseShapeService) {
        this.daoGatewayClient = daoGatewayClient;
        this.requestUserResolver = requestUserResolver;
        this.responseShapeService = responseShapeService;
    }

    @GetMapping
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID userId,
                         @RequestParam(required = false) UUID campaignId,
                         @RequestParam(required = false) UUID creatorId,
                         @RequestParam(required = false) String stage,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        query.put("campaignId", campaignId == null ? null : campaignId.toString());
        query.put("creatorId", creatorId == null ? null : creatorId.toString());
        query.put("stage", stage);
        return responseShapeService.campaignCreatorsList(daoGatewayClient.get("/campaign-creators", query), page, size);
    }

    @GetMapping("/{id}")
    public JsonNode findById(@PathVariable UUID id) {
        return responseShapeService.campaignCreator(daoGatewayClient.get("/campaign-creators/" + id, null));
    }

    @PostMapping
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        payload.put("userId", resolvedUserId.toString());
        return responseShapeService.campaignCreator(daoGatewayClient.post("/campaign-creators", payload));
    }

    @PutMapping("/{id}")
    public JsonNode update(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        payload.put("userId", resolvedUserId.toString());
        return responseShapeService.campaignCreator(daoGatewayClient.put("/campaign-creators/" + id, payload));
    }

    @PatchMapping("/{id}/stage")
    public JsonNode updateStage(@PathVariable UUID id, @RequestBody ObjectNode payload) {
        return responseShapeService.campaignCreator(daoGatewayClient.patch("/campaign-creators/" + id + "/stage", payload));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        daoGatewayClient.delete("/campaign-creators/" + id);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
