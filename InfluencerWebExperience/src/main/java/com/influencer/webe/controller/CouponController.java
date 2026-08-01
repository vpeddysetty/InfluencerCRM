package com.influencer.webe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.service.CouponService;
import com.influencer.webe.service.MarketplaceService;
import com.influencer.webe.service.RequestUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Coupon generation endpoints (Phase 1). Sits alongside the plain CRUD in
 * {@code InfluencerTrackingController} — this controller owns the *generation*
 * logic (template expansion, bulk fan-out, uniqueness) via {@link CouponService}.
 */
@RestController
@RequestMapping("/api/coupons")
public class CouponController {
    private final CouponService couponService;
    private final MarketplaceService marketplaceService;
    private final RequestUserResolver requestUserResolver;

    public CouponController(CouponService couponService,
                           MarketplaceService marketplaceService,
                           RequestUserResolver requestUserResolver) {
        this.couponService = couponService;
        this.marketplaceService = marketplaceService;
        this.requestUserResolver = requestUserResolver;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode generate(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestBody ObjectNode payload) {
        UUID userId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        return couponService.generateOne(userId, payload);
    }

    @PostMapping("/generate-bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode generateBulk(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestBody ObjectNode payload) {
        UUID userId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        return couponService.generateBulk(userId, payload);
    }

    /**
     * Push a locally-created coupon to a connected marketplace via its adapter.
     * Body may carry {@code { connectionId }} to override the coupon's stored one.
     */
    @PostMapping("/{id}/push")
    public JsonNode push(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @PathVariable UUID id,
                         @RequestBody(required = false) ObjectNode payload) {
        UUID userId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        UUID connectionId = getUuid(payload, "connectionId");
        return marketplaceService.pushCoupon(userId, id, connectionId);
    }

    /**
     * Submit creator personalization (blurb + optional embed) for a coupon's
     * landing page. Sets personalization_status = 'pending' (awaits brand approval).
     */
    @PostMapping("/{id}/personalize")
    public JsonNode personalize(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable UUID id,
                                @RequestBody ObjectNode payload) {
        UUID userId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        return couponService.personalize(userId, id, payload);
    }

    /**
     * Brand approves or rejects a coupon's pending personalization.
     * {decision} = "approve" | "reject".
     */
    @PostMapping("/{id}/personalization/{decision}")
    public JsonNode decidePersonalization(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable UUID id,
                                          @PathVariable String decision,
                                          @RequestBody(required = false) ObjectNode payload) {
        UUID userId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        return couponService.decidePersonalization(userId, id, decision);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
