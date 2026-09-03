package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.content.application.LandingAnalyticsService;
import com.influencer.webe.content.application.PageLeadService;
import com.influencer.webe.content.application.ShareKitService;
import com.influencer.webe.content.application.LandingService;
import com.influencer.webe.identity.application.EntitlementService;
import com.influencer.webe.identity.application.PlanPolicy;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    private final EntitlementService entitlements;
    private final LandingAnalyticsService landingAnalytics;
    private final ShareKitService shareKit;
    private final PageLeadService pageLeads;

    public LandingController(LandingService landingService,
                            DaoGatewayClient dao,
                            RequestUserResolver requestUserResolver,
                            ResponseShapeService shape,
                            EntitlementService entitlements,
                            LandingAnalyticsService landingAnalytics,
                            ShareKitService shareKit,
                            PageLeadService pageLeads) {
        this.landingService = landingService;
        this.dao = dao;
        this.requestUserResolver = requestUserResolver;
        this.shape = shape;
        this.entitlements = entitlements;
        this.landingAnalytics = landingAnalytics;
        this.shareKit = shareKit;
        this.pageLeads = pageLeads;
    }

    // PR-39: `GET /api/landing-templates/editor` and the `web-experience.landing.editor` flag are
    // both gone. The flag existed so `sections` could be rolled back to the GrapesJS builder with a
    // variable flip; GrapesJS is deleted from the bundle, so there is nothing to roll back TO and a
    // config endpoint with one possible answer is a question nobody needs to ask.

    // ---- brand-authenticated template management -----------------------
    @GetMapping("/api/landing-templates")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID brandId,
                         @RequestParam(required = false) UUID campaignId,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        UUID resolved = requestUserResolver.resolveBrandId(authorization, brandId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolved.toString());
        query.put("campaignId", campaignId == null ? null : campaignId.toString());
        return shape.landingTemplatesList(dao.get("/landing-templates", query), page, size);
    }

    /** Upsert the campaign's landing template + (re)assign coupon slugs. */
    @PostMapping("/api/landing-templates/save")
    public JsonNode save(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requireTenantContext(authorization);
        UUID brandId = requestUserResolver.resolveBrandId(authorization, getUuid(payload, "brandId"));

        // M2.3. This endpoint is an UPSERT, so the limit must apply only when it would actually
        // create a page. Checking unconditionally would block every edit to an existing page the
        // moment an account reached its limit — turning a cap on how many pages you may have into
        // a cap on whether you may edit the ones you already have.
        if (!landingService.existsForCampaign(brandId, getUuid(payload, "campaignId"))) {
            entitlements.requireCapacity(context.accountId(), PlanPolicy.Resource.LANDING_PAGE);
        }
        return landingService.saveTemplate(brandId, payload);
    }

    /**
     * Preview the current (possibly unsaved) landing blocks, personalized for a
     * chosen coupon. Brand-auth'd; does NOT persist and does NOT record a view.
     * Returns rendered HTML for the builder's live preview.
     */
    @PostMapping(value = "/api/landing-templates/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.resolveBrandId(authorization, getUuid(payload, "brandId"));
        return landingService.previewTemplate(brandId, payload);
    }

    /** Landing-page views (click funnel) for the tenant or a specific coupon. */
    @GetMapping("/api/landing-page-views")
    public JsonNode views(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestParam(required = false) UUID brandId,
                          @RequestParam(required = false) UUID campaignCodeId) {
        UUID resolved = requestUserResolver.resolveBrandId(authorization, brandId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolved.toString());
        query.put("campaignCodeId", campaignCodeId == null ? null : campaignCodeId.toString());
        return dao.get("/landing-page-views", query);
    }

    /**
     * View counts for a campaign's landing page, by creator and by day (PR-57).
     *
     * <p>Separate from {@code /api/landing-page-views} above, which returns raw rows: that one is
     * unpaginated and undated, so a page doing well would eventually answer with tens of thousands
     * of records for the browser to add up. This returns the summary, counted server-side.
     *
     * <p>Permission is {@code CONTENT_READ} rather than a reporting-specific one: this is the
     * page's own performance, shown to whoever may already see the page.
     */
    @GetMapping("/api/landing-pages/analytics")
    public JsonNode analytics(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestParam UUID campaignId,
                              @RequestParam(required = false) UUID brandId,
                              @RequestParam(required = false) Integer days) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        return landingAnalytics.forCampaign(resolved, campaignId, days);
    }

    /**
     * The share kit for one creator's code on a published page (PR-45).
     *
     * <p>CONTENT_READ: it assembles words already on the page and a link already public. The
     * creator-facing route is the portal's own, which authenticates with X-Creator-Token; this one
     * is for the brand, who often posts on a creator's behalf or sends them the text.
     */
    @GetMapping("/api/landing-pages/{templateId}/share-kit")
    public JsonNode shareKit(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable UUID templateId,
                             @RequestParam UUID couponId,
                             @RequestParam(required = false) UUID brandId) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        return shareKit.forCoupon(resolved, templateId, couponId);
    }

    /**
     * Record that a creator posted this page (PR-45).
     *
     * <p>CONTENT_WRITE rather than READ: it writes a row a brand will act on. The creator-facing
     * equivalent lives on the portal's own controller, where the actor is a creator identity
     * rather than a user.
     */
    @PostMapping("/api/landing-pages/{templateId}/posted")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode recordPosted(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @PathVariable UUID templateId,
                                 @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CONTENT_WRITE);
        UUID couponId = getUuid(payload, "couponId");
        if (couponId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "couponId is required");
        }
        return shareKit.recordPosted(context.brandId(), templateId, couponId,
                context.userId(), null, payload.path("platform").asText(null));
    }

    /** What has been reported as posted for this page, newest first (PR-45). */
    @GetMapping("/api/landing-pages/{templateId}/posted")
    public JsonNode postedClaims(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @PathVariable UUID templateId) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        return shareKit.postsFor(resolved, templateId);
    }

    /**
     * Lead capture from a published public page (PR-61) — <b>no authentication</b>.
     *
     * <p>Public by necessity: a visitor filling in a form on a brand's landing page has no account,
     * so this cannot require a token. Four things contain what that opens up — the tenant comes
     * from the SLUG rather than the caller, an unpublished page is not addressable, consent is
     * required before anything is written, and the endpoint is rate limited per page.
     *
     * <p>The response is deliberately thin. A public caller learns that it worked and nothing about
     * the brand, the page id, or what else was stored.
     */
    @PostMapping("/api/public/landing/{slug}/leads")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode captureLead(@PathVariable String slug,
                                @RequestBody ObjectNode payload,
                                HttpServletRequest httpRequest) {
        Boolean accepted = payload.hasNonNull("acceptedTerms") ? payload.get("acceptedTerms").asBoolean() : null;
        return pageLeads.capture(slug, payload, accepted, httpRequest);
    }

    /** The enquiries a page has collected, newest first (PR-61). Brand-side. */
    @GetMapping("/api/landing-pages/{templateId}/leads")
    public JsonNode leads(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @PathVariable UUID templateId) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        return pageLeads.listFor(resolved, templateId);
    }

    /** Version history for the campaign's landing page, newest first (A.5). */
    @GetMapping("/api/landing-templates/versions")
    public JsonNode versions(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestParam UUID campaignId,
                             @RequestParam(required = false) UUID brandId) {
        UUID resolved = requestUserResolver.resolveBrandId(authorization, brandId);
        return landingService.listVersions(resolved, campaignId);
    }

    /**
     * Restore a previous version. Writes the old content forward as a new save, so the
     * restore itself becomes the newest version rather than erasing what it undid.
     */
    @PostMapping("/api/landing-templates/versions/{versionNo}/restore")
    public JsonNode restoreVersion(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable int versionNo,
                                   @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.resolveBrandId(authorization, getUuid(payload, "brandId"));
        UUID campaignId = getUuid(payload, "campaignId");
        if (campaignId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "campaignId is required");
        }
        return landingService.restoreVersion(brandId, campaignId, versionNo);
    }

    // ---- public hosted landing page (NO AUTH) --------------------------
    @GetMapping(value = "/s/{slug}/{creator}", produces = MediaType.TEXT_HTML_VALUE)
    public String publicPage(@PathVariable String slug,
                             @PathVariable String creator,
                             @RequestHeader(value = "Referer", required = false) String referer,
                             @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return landingService.renderPublic(slug, creator, referer, userAgent);
    }

    /**
     * The brand's own page — no creator, no coupon (Phase A).
     *
     * Before this existed a saved page was unreachable until a creator coupon was
     * assigned, which made the builder unusable on its own. Serves only published pages.
     */
    @GetMapping(value = "/s/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public String publicBrandPage(@PathVariable String slug,
                                  @RequestHeader(value = "Referer", required = false) String referer,
                                  @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return landingService.renderPublicBrandPage(slug, referer, userAgent);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
