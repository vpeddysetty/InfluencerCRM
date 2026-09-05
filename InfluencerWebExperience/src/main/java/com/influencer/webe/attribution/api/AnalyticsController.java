package com.influencer.webe.attribution.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.attribution.application.AnalyticsService;
import com.influencer.webe.attribution.application.PortfolioService;
import com.influencer.webe.attribution.application.ReportCsvWriter;
import org.springframework.http.ResponseEntity;
import java.time.ZoneOffset;
import java.util.List;
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
    private final ReportCsvWriter csvWriter;

    public AnalyticsController(AnalyticsService analyticsService,
                              RequestUserResolver requestUserResolver,
                               PortfolioService portfolioService,
                               ReportCsvWriter csvWriter) {
        this.analyticsService = analyticsService;
        this.requestUserResolver = requestUserResolver;
        this.portfolioService = portfolioService;
        this.csvWriter = csvWriter;
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

    /**
     * The portfolio as a CSV an agency can send its client (roadmap PR-65).
     *
     * <p>Same service, same permission, same window as the JSON above — only the rendering differs.
     * That is the point of putting the export here rather than in a browser: the figures in the file
     * are the figures on the screen, computed once, by the context that owns what they mean.
     */
    @GetMapping(value = "/portfolio.csv", produces = "text/csv")
    public ResponseEntity<String> portfolioCsv(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        TenantContext context =
                requestUserResolver.requirePermission(authorization, Permission.ATTRIBUTION_READ);
        JsonNode report = portfolioService.portfolio(context.userId(), from, to);

        String csv = csvWriter.toCsv(List.of(
                new ReportCsvWriter.Column("Client", "brandName"),
                new ReportCsvWriter.Column("Revenue", "revenue"),
                new ReportCsvWriter.Column("Orders", "orders"),
                new ReportCsvWriter.Column("Creators", "creators"),
                new ReportCsvWriter.Column("Commission", "commission"),
                new ReportCsvWriter.Column("Creator cost", "influencerCost"),
                new ReportCsvWriter.Column("ROI", "roi")), report.get("brands"));

        return csvResponse(csv, csvWriter.filename("portfolio", LocalDate.now(ZoneOffset.UTC)));
    }

    /** One brand's creator leaderboard as a CSV (roadmap PR-65). */
    @GetMapping(value = "/influencer-revenue.csv", produces = "text/csv")
    public ResponseEntity<String> influencerRevenueCsv(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.ATTRIBUTION_READ);
        JsonNode report = analyticsService.influencerRevenue(resolved, from, to);

        String csv = csvWriter.toCsv(List.of(
                new ReportCsvWriter.Column("Creator", "creatorName"),
                new ReportCsvWriter.Column("Orders", "orders"),
                new ReportCsvWriter.Column("Revenue", "revenue"),
                new ReportCsvWriter.Column("Average order value", "avgOrderValue"),
                new ReportCsvWriter.Column("Commission", "commission")), report.get("leaderboard"));

        return csvResponse(csv, csvWriter.filename("creator-revenue", LocalDate.now(ZoneOffset.UTC)));
    }

    /**
     * {@code attachment}, so the browser saves rather than renders.
     *
     * <p>Rendering a CSV inline would let a crafted field be interpreted by the browser on our own
     * origin; the filename is quoted for the same reason a field is. The charset is stated even
     * though the body carries a BOM — a client reading the header should not have to guess.
     */
    private ResponseEntity<String> csvResponse(String csv, String filename) {
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }
}
