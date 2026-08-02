package com.influencer.webe.attribution.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BFF pass-through for a brand's connected external marketplaces
 * (Phase 0/1 substrate; the SPI that actually talks to Shopify lands in Phase 2).
 * Credentials are never returned to the UI — see {@code marketplaceConnection} shaper.
 */
@RestController
@RequestMapping("/api")
public class MarketplaceConnectionsController {
    private final DaoGatewayClient dao;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService shape;

    public MarketplaceConnectionsController(DaoGatewayClient dao,
                                            RequestUserResolver requestUserResolver,
                                            ResponseShapeService shape) {
        this.dao = dao;
        this.requestUserResolver = requestUserResolver;
        this.shape = shape;
    }

    @GetMapping("/marketplace-connections")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID brandId,
                         @RequestParam(required = false) String providerKey,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.COUPON_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolved.toString());
        query.put("providerKey", providerKey);
        return shape.marketplaceConnectionsList(dao.get("/marketplace-connections", query), page, size);
    }

    @GetMapping("/marketplace-connections/{id}")
    public JsonNode byId(@PathVariable UUID id) {
        return shape.marketplaceConnection(dao.get("/marketplace-connections/" + id, null));
    }

    @PostMapping("/marketplace-connections")
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.MARKETPLACE_CONNECT).toString());
        return shape.marketplaceConnection(dao.post("/marketplace-connections", payload));
    }

    @PutMapping("/marketplace-connections/{id}")
    public JsonNode update(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.resolveBrandId(authorization, getUuid(payload, "brandId")).toString());
        return shape.marketplaceConnection(dao.put("/marketplace-connections/" + id, payload));
    }

    @DeleteMapping("/marketplace-connections/{id}")
    public void delete(@PathVariable UUID id) {
        dao.delete("/marketplace-connections/" + id);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
