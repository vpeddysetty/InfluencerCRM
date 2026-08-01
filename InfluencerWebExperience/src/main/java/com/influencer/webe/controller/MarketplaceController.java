package com.influencer.webe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.service.MarketplaceService;
import com.influencer.webe.service.RequestUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Marketplace SPI endpoints (Phase 2): list available providers + capabilities,
 * and run the connect handshake that validates credentials and persists a
 * connection. Coupon push lives on {@code /api/coupons/{id}/push} (CouponController).
 */
@RestController
@RequestMapping("/api")
public class MarketplaceController {
    private final MarketplaceService marketplaceService;
    private final RequestUserResolver requestUserResolver;

    public MarketplaceController(MarketplaceService marketplaceService,
                                RequestUserResolver requestUserResolver) {
        this.marketplaceService = marketplaceService;
        this.requestUserResolver = requestUserResolver;
    }

    /** Catalog of supported marketplaces + their capabilities (no auth-scoped data). */
    @GetMapping("/marketplace-providers")
    public JsonNode listProviders() {
        return marketplaceService.listProviders();
    }

    /**
     * Connect a store: {@code { providerKey, credentials: { apiKey, shop, ... } }}.
     * Validates via the adapter, then persists a marketplace_connections row.
     */
    @PostMapping("/marketplace-connections/connect")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode connect(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestBody ObjectNode payload) {
        UUID userId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        String providerKey = payload.hasNonNull("providerKey") ? payload.get("providerKey").asText() : null;
        if (providerKey == null || providerKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerKey is required");
        }
        return marketplaceService.connect(userId, providerKey, extractCredentials(payload));
    }

    private Map<String, String> extractCredentials(ObjectNode payload) {
        Map<String, String> creds = new HashMap<>();
        JsonNode node = payload.get("credentials");
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    creds.put(entry.getKey(), entry.getValue().asText());
                }
            }
        }
        return creds;
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
