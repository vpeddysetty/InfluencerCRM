package com.influencer.webe.campaign.api;

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
@RequestMapping("/api/campaigns")
public class CampaignsController {
    private final DaoGatewayClient daoGatewayClient;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService responseShapeService;

    public CampaignsController(DaoGatewayClient daoGatewayClient,
                               RequestUserResolver requestUserResolver,
                               ResponseShapeService responseShapeService) {
        this.daoGatewayClient = daoGatewayClient;
        this.requestUserResolver = requestUserResolver;
        this.responseShapeService = responseShapeService;
    }

    @GetMapping
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID brandId,
                         @RequestParam(required = false) String campaignType,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolvedBrandId.toString());
        query.put("campaignType", campaignType);
        return responseShapeService.campaignsList(daoGatewayClient.get("/campaigns", query), page, size);
    }

    @GetMapping("/{id}")
    public JsonNode findById(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable UUID id) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_READ);
        return responseShapeService.campaign(requireBrandOwned(id, resolvedBrandId));
    }

    @PostMapping
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CAMPAIGN_WRITE);
        payload.put("brandId", context.brandId().toString());
        // From the verified token, not the body — see CreatorsController#create.
        payload.put("createdByUserId", context.userId().toString());
        return responseShapeService.campaign(daoGatewayClient.post("/campaigns", payload));
    }

    @PutMapping("/{id}")
    public JsonNode update(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_WRITE);
        payload.put("brandId", resolvedBrandId.toString());
        return responseShapeService.campaign(daoGatewayClient.put("/campaigns/" + id, payload));
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable UUID id) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CAMPAIGN_DELETE);
        requireBrandOwned(id, resolvedBrandId);
        daoGatewayClient.delete("/campaigns/" + id);
    }

    /**
     * Fetches a campaign and asserts it belongs to the caller's brand.
     *
     * <p>See {@code CreatorsController#requireBrandOwned}: a lookup by id carries no brand filter,
     * so ownership has to be checked here rather than inferred from the route. 404 rather than 403
     * so the response does not confirm that an id the caller cannot reach exists.
     */
    private JsonNode requireBrandOwned(UUID id, UUID resolvedBrandId) {
        JsonNode existing = daoGatewayClient.get("/campaigns/" + id, null);
        String ownerBrandId = existing != null && existing.hasNonNull("brandId")
                ? existing.get("brandId").asText()
                : null;
        if (ownerBrandId == null || !resolvedBrandId.toString().equals(ownerBrandId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found");
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
