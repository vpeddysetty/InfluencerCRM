package com.influencer.webe.attribution.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.attribution.application.AnalyticsService;
import com.influencer.webe.attribution.application.PortfolioService;
import com.influencer.webe.security.TenantContext;
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
    private final PortfolioService portfolioService;

    public AnalyticsController(AnalyticsService analyticsService,
                              RequestUserResolver requestUserResolver,
                               PortfolioService portfolioService) {
        this.analyticsService = analyticsService;
        this.requestUserResolver = requestUserResolver;
        this.portfolioService = portfolioService;
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

    /**
     * The same figures across EVERY brand the caller may reach (roadmap PR-64).
     *
     * <p><b>Account-scoped, not brand-scoped</b> — the one endpoint here that is. It takes no
     * {@code brandId} on purpose: the set of brands is derived from the caller's own access, so a
     * client cannot ask about a brand it does not hold by naming it. `requireTenantContext` gives
     * the verified user id; `BrandAccessPort` decides what that user reaches.
     *
     * <p><b>`ATTRIBUTION_READ` is checked account-wide rather than per brand.</b> The permission
     * answers "may this person see revenue figures at all"; which figures they see is settled by
     * the brand list, and checking the same permission once per brand would re-ask a question the
     * membership already answered. `ANALYST` is read-only and holds it; `MARKETER` and above hold
     * it for the brands they are granted.
     */
    @GetMapping("/portfolio")
    public JsonNode portfolio(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        TenantContext context =
                requestUserResolver.requirePermission(authorization, Permission.ATTRIBUTION_READ);
        return portfolioService.portfolio(context.userId(), from, to);
    }
}
