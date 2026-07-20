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
public class InfluencerTrackingController {
    private final DaoGatewayClient daoGatewayClient;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService responseShapeService;

    public InfluencerTrackingController(DaoGatewayClient daoGatewayClient,
                                        RequestUserResolver requestUserResolver,
                                        ResponseShapeService responseShapeService) {
        this.daoGatewayClient = daoGatewayClient;
        this.requestUserResolver = requestUserResolver;
        this.responseShapeService = responseShapeService;
    }

    @GetMapping("/influencer-campaign-codes")
    public JsonNode listCodes(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestParam(required = false) UUID userId,
                              @RequestParam(required = false) UUID campaignId,
                              @RequestParam(required = false) UUID creatorId,
                              @RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        query.put("campaignId", campaignId == null ? null : campaignId.toString());
        query.put("creatorId", creatorId == null ? null : creatorId.toString());
        return responseShapeService.campaignCodesList(daoGatewayClient.get("/influencer-campaign-codes", query), page, size);
    }

    @GetMapping("/influencer-campaign-codes/{id}")
    public JsonNode codeById(@PathVariable UUID id) { return responseShapeService.campaignCode(daoGatewayClient.get("/influencer-campaign-codes/" + id, null)); }

    @PostMapping("/influencer-campaign-codes")
    public JsonNode createCode(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.campaignCode(daoGatewayClient.post("/influencer-campaign-codes", payload));
    }

    @PutMapping("/influencer-campaign-codes/{id}")
    public JsonNode updateCode(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable UUID id,
                               @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.campaignCode(daoGatewayClient.put("/influencer-campaign-codes/" + id, payload));
    }

    @DeleteMapping("/influencer-campaign-codes/{id}")
    public void deleteCode(@PathVariable UUID id) { daoGatewayClient.delete("/influencer-campaign-codes/" + id); }

    @GetMapping("/influencer-sale-attributions")
    public JsonNode listAttributions(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestParam(required = false) UUID userId,
                                     @RequestParam(required = false) UUID campaignCodeId,
                                     @RequestParam(required = false) UUID campaignCreatorId,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        query.put("campaignCodeId", campaignCodeId == null ? null : campaignCodeId.toString());
        query.put("campaignCreatorId", campaignCreatorId == null ? null : campaignCreatorId.toString());
        return responseShapeService.saleAttributionsList(daoGatewayClient.get("/influencer-sale-attributions", query), page, size);
    }

    @GetMapping("/influencer-sale-attributions/{id}")
    public JsonNode attributionById(@PathVariable UUID id) { return responseShapeService.saleAttribution(daoGatewayClient.get("/influencer-sale-attributions/" + id, null)); }

    @PostMapping("/influencer-sale-attributions")
    public JsonNode createAttribution(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.saleAttribution(daoGatewayClient.post("/influencer-sale-attributions", payload));
    }

    @PutMapping("/influencer-sale-attributions/{id}")
    public JsonNode updateAttribution(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable UUID id,
                                      @RequestBody ObjectNode payload) {
        payload.put("userId", requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId")).toString());
        return responseShapeService.saleAttribution(daoGatewayClient.put("/influencer-sale-attributions/" + id, payload));
    }

    @DeleteMapping("/influencer-sale-attributions/{id}")
    public void deleteAttribution(@PathVariable UUID id) { daoGatewayClient.delete("/influencer-sale-attributions/" + id); }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
