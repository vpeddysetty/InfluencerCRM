package com.influencer.webe.campaign.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.campaign.infrastructure.AgentMappingClient;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Campaign content brief (Content Phase 1). Brand-authored brief per campaign:
 * summary/goals/dos-donts (content), asset links, hashtags, disclosure text.
 * jsonb fields are serialized to strings before forwarding to the DAO.
 */
@RestController
@RequestMapping("/api")
public class CampaignBriefsController {
    private final DaoGatewayClient dao;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService shape;
    private final AgentMappingClient agentClient;

    public CampaignBriefsController(DaoGatewayClient dao,
                                    RequestUserResolver requestUserResolver,
                                    ResponseShapeService shape,
                                    AgentMappingClient agentClient) {
        this.dao = dao;
        this.requestUserResolver = requestUserResolver;
        this.shape = shape;
        this.agentClient = agentClient;
    }

    /**
     * LLM draft assist (Content Phase 4). Proxies to agent_service, which returns
     * an LLM draft or a deterministic heuristic fallback if the model is unavailable.
     */
    @PostMapping("/content/draft")
    public JsonNode draft(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestBody ObjectNode payload) {
        requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE); // auth gate
        payload.remove("brandId");
        return agentClient.draftContent(payload);
    }

    @GetMapping("/campaign-briefs")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID brandId,
                         @RequestParam(required = false) UUID campaignId,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolved.toString());
        query.put("campaignId", campaignId == null ? null : campaignId.toString());
        return shape.campaignBriefsList(dao.get("/campaign-briefs", query), page, size);
    }

    @GetMapping("/campaign-briefs/{id}")
    public JsonNode byId(@PathVariable UUID id) {
        return shape.campaignBrief(dao.get("/campaign-briefs/" + id, null));
    }

    @PostMapping("/campaign-briefs")
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE).toString());
        stringifyJsonb(payload, "content", "assets", "hashtags");
        return shape.campaignBrief(dao.post("/campaign-briefs", payload));
    }

    @PutMapping("/campaign-briefs/{id}")
    public JsonNode update(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE).toString());
        stringifyJsonb(payload, "content", "assets", "hashtags");
        return shape.campaignBrief(dao.put("/campaign-briefs/" + id, payload));
    }

    @DeleteMapping("/campaign-briefs/{id}")
    public void delete(@PathVariable UUID id) {
        dao.delete("/campaign-briefs/" + id);
    }

    /** Serialize object/array jsonb fields to JSON strings for the DAO's String columns. */
    private void stringifyJsonb(ObjectNode payload, String... fields) {
        for (String field : fields) {
            JsonNode node = payload.get(field);
            if (node != null && (node.isObject() || node.isArray())) {
                try {
                    payload.put(field, shape.objectMapper().writeValueAsString(node));
                } catch (Exception ignored) {
                    // leave as-is; DAO will reject if malformed
                }
            }
        }
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
