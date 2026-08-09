package com.influencer.webe.creator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
                         @RequestParam(required = false) UUID brandId,
                         @RequestParam(required = false) UUID campaignId,
                         @RequestParam(required = false) UUID creatorId,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolvedBrandId.toString());
        query.put("campaignId", campaignId == null ? null : campaignId.toString());
        query.put("creatorId", creatorId == null ? null : creatorId.toString());
        return responseShapeService.campaignCreatorsList(daoGatewayClient.get("/campaign-creators", query), page, size);
    }

    @GetMapping("/{id}")
    public JsonNode findById(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable UUID id) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_READ);
        return responseShapeService.campaignCreator(requireBrandOwned(id, resolvedBrandId));
    }

    @PostMapping
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_CREATOR_ASSIGN);
        payload.put("brandId", resolvedBrandId.toString());
        return responseShapeService.campaignCreator(daoGatewayClient.post("/campaign-creators", payload));
    }

    @PutMapping("/{id}")
    public JsonNode update(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_CREATOR_ASSIGN);
        payload.put("brandId", resolvedBrandId.toString());
        return responseShapeService.campaignCreator(daoGatewayClient.put("/campaign-creators/" + id, payload));
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable UUID id) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_CREATOR_ASSIGN);
        requireBrandOwned(id, resolvedBrandId);
        daoGatewayClient.delete("/campaign-creators/" + id);
    }

    /**
     * Fetches an assignment and asserts it belongs to the caller's brand.
     *
     * <p>See {@code CreatorsController#requireBrandOwned}: the list route filters by brand, a lookup
     * by id does not, so ownership is checked explicitly. 404 rather than 403 so the response does
     * not confirm the existence of a row the caller cannot reach.
     */
    private JsonNode requireBrandOwned(UUID id, UUID resolvedBrandId) {
        JsonNode existing = daoGatewayClient.get("/campaign-creators/" + id, null);
        String ownerBrandId = existing != null && existing.hasNonNull("brandId")
                ? existing.get("brandId").asText()
                : null;
        if (ownerBrandId == null || !resolvedBrandId.toString().equals(ownerBrandId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign creator not found");
        }
        return existing;
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
