package com.influencer.webe.creator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.creator.application.CreatorHealthService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Creator health monitoring (roadmap C3).
 *
 * <p>There is deliberately no endpoint here that changes a creator's standing. The most an
 * alert can do is be acknowledged, snoozed, or marked as acted upon — the decision to keep,
 * pause or end a relationship stays with a person (roadmap #13).
 */
@RestController
public class CreatorHealthController {

    private final CreatorHealthService health;
    private final RequestUserResolver requestUserResolver;

    public CreatorHealthController(CreatorHealthService health, RequestUserResolver requestUserResolver) {
        this.health = health;
        this.requestUserResolver = requestUserResolver;
    }

    // ---- thresholds (C3.3) ----------------------------------------------

    @GetMapping("/api/health-thresholds")
    public JsonNode thresholds(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return health.thresholds(brandId);
    }

    @PostMapping("/api/health-thresholds")
    public JsonNode saveThresholds(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        return health.saveThresholds(brandId, payload);
    }

    // ---- refresh and history (C3.1, C3.2, C3.6) -------------------------

    /**
     * Re-read a creator's metrics, snapshot them, and raise any warranted alerts.
     *
     * <p>Manual here; a scheduler would call the same path on the tiered cadence the roadmap
     * describes (weekly for creators on active campaigns, monthly otherwise). That cadence is a
     * direct cost line, not just a scheduling choice, because each refresh spends API quota.
     */
    @PostMapping("/api/creators/{id}/health/refresh")
    public JsonNode refresh(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        return health.refresh(brandId, id);
    }

    /** The trend: the series, not just the current number (C3.6). */
    @GetMapping("/api/creators/{id}/health/history")
    public JsonNode history(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return health.history(brandId, id);
    }

    // ---- the alert queue (C3.5) -----------------------------------------

    @GetMapping("/api/health-alerts")
    public JsonNode alerts(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestParam(required = false) String status) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return health.alerts(brandId, status);
    }

    /**
     * Acknowledge, snooze, or record that someone acted.
     *
     * <p>Requires a user: "we saw this and decided to keep them" is as much a decision as ending
     * the relationship, and both belong on the record with a name attached.
     */
    @PutMapping("/api/health-alerts/{id}")
    public JsonNode resolve(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable UUID id,
                            @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CREATOR_WRITE);
        Instant snoozedUntil = null;
        if (payload.hasNonNull("snoozedUntil")) {
            try {
                snoozedUntil = Instant.parse(payload.get("snoozedUntil").asText());
            } catch (Exception ignored) {
                // An unparseable date falls back to the default snooze window rather than failing.
            }
        }
        return health.resolve(context.brandId(), id, context.userId(),
                payload.path("status").asText(null),
                payload.path("note").asText(null),
                snoozedUntil);
    }
}
