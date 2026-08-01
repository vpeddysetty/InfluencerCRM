package com.influencer.webe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.client.DaoGatewayClient;
import com.influencer.webe.service.LandingService;
import com.influencer.webe.service.RequestUserResolver;
import com.influencer.webe.service.ResponseShapeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Landing templates + the public hosted page (Content Phase 2).
 *
 * - `/api/landing-templates` — brand-auth'd template read + `POST /save` (upsert per campaign).
 * - `/s/{slug}/{creator}` — PUBLIC hosted landing page (no auth); renders sanitized HTML and
 *   records a landing-page view (click attribution).
 */
@RestController
public class LandingController {
    private final LandingService landingService;
    private final DaoGatewayClient dao;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService shape;

    public LandingController(LandingService landingService,
                            DaoGatewayClient dao,
                            RequestUserResolver requestUserResolver,
                            ResponseShapeService shape) {
        this.landingService = landingService;
        this.dao = dao;
        this.requestUserResolver = requestUserResolver;
        this.shape = shape;
    }

    // ---- brand-authenticated template management -----------------------
    @GetMapping("/api/landing-templates")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID userId,
                         @RequestParam(required = false) UUID campaignId,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        UUID resolved = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolved.toString());
        query.put("campaignId", campaignId == null ? null : campaignId.toString());
        return shape.landingTemplatesList(dao.get("/landing-templates", query), page, size);
    }

    /** Upsert the campaign's landing template + (re)assign coupon slugs. */
    @PostMapping("/api/landing-templates/save")
    public JsonNode save(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestBody ObjectNode payload) {
        UUID userId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        return landingService.saveTemplate(userId, payload);
    }

    /** Landing-page views (click funnel) for the tenant or a specific coupon. */
    @GetMapping("/api/landing-page-views")
    public JsonNode views(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestParam(required = false) UUID userId,
                          @RequestParam(required = false) UUID campaignCodeId) {
        UUID resolved = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolved.toString());
        query.put("campaignCodeId", campaignCodeId == null ? null : campaignCodeId.toString());
        return dao.get("/landing-page-views", query);
    }

    // ---- public hosted landing page (NO AUTH) --------------------------
    @GetMapping(value = "/s/{slug}/{creator}", produces = MediaType.TEXT_HTML_VALUE)
    public String publicPage(@PathVariable String slug,
                             @PathVariable String creator,
                             @RequestHeader(value = "Referer", required = false) String referer,
                             @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return landingService.renderPublic(slug, creator, referer, userAgent);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
