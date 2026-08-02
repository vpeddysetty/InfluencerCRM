package com.influencer.webe.attribution.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.attribution.application.AttributionService;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Order ingestion endpoints (Phase 3).
 *
 * - {@code POST /api/webhooks/marketplace/{providerKey}} is the public webhook a
 *   marketplace calls; it must carry a {@code brandId} (resolved from the store
 *   mapping in a real integration). Signature verification is delegated to the
 *   provider adapter (mock trusts all).
 * - {@code POST /api/attribution/simulate} is an auth-scoped test hook that feeds
 *   a synthetic order through the same pipeline (used for E2E testing / demos).
 */
@RestController
@RequestMapping("/api")
public class WebhookController {
    private final AttributionService attributionService;
    private final RequestUserResolver requestUserResolver;

    public WebhookController(AttributionService attributionService,
                            RequestUserResolver requestUserResolver) {
        this.attributionService = attributionService;
        this.requestUserResolver = requestUserResolver;
    }

    @PostMapping("/webhooks/marketplace/{providerKey}")
    @ResponseStatus(HttpStatus.OK)
    public JsonNode webhook(@PathVariable String providerKey,
                            @RequestParam(required = false) UUID brandId,
                            @RequestBody ObjectNode payload) {
        UUID resolved = brandId != null ? brandId : getUuid(payload, "brandId");
        if (resolved == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "brandId is required for webhook ingestion");
        }
        return attributionService.ingest(resolved, providerKey, payload);
    }

    /**
     * Auth-scoped simulation of an order for testing. Body:
     * {@code { providerKey, order: { code, orderId, saleAmount, discountAmount, status, ... } }}.
     */
    @PostMapping("/attribution/simulate")
    public JsonNode simulate(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.resolveBrandId(authorization, getUuid(payload, "brandId"));
        String providerKey = payload.hasNonNull("providerKey") ? payload.get("providerKey").asText() : "mock";
        JsonNode order = payload.get("order");
        if (order == null || !order.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order object is required");
        }
        return attributionService.ingest(brandId, providerKey, order);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
