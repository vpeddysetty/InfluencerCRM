package com.influencer.webe.attribution.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.attribution.application.AnalyticsService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Dashboard analytics (Phase 4): shaped, pre-aggregated influencer-revenue data
 * for the UI dashboard.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsService analyticsService;
    private final RequestUserResolver requestUserResolver;

    public AnalyticsController(AnalyticsService analyticsService,
                              RequestUserResolver requestUserResolver) {
        this.analyticsService = analyticsService;
        this.requestUserResolver = requestUserResolver;
    }

    /**
     * @param from inclusive start date (ISO yyyy-MM-dd, UTC); omit for open-ended
     * @param to   inclusive end date (ISO yyyy-MM-dd, UTC); omit for open-ended
     */
    @GetMapping("/influencer-revenue")
    public JsonNode influencerRevenue(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestParam(required = false) UUID brandId,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.ATTRIBUTION_READ);
        return analyticsService.influencerRevenue(resolved, from, to);
    }
}
