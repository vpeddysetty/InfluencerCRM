package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.CampaignPageGenerationService;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * AI campaign-page authoring: brief in, drafts out (roadmap PR-35).
 *
 * <p><b>Generation only.</b> Selecting a draft is a save through
 * {@code POST /api/landing-templates/save}, which already upserts the campaign's template, assigns
 * coupon slugs and snapshots a version. Adding a parallel "select this variant" write path would
 * mean a second way to create a landing page, and the two would drift.
 *
 * <p><b>Brand-authenticated, and no plan check here.</b> The landing-page entitlement is enforced
 * on save, where a page is actually created. Charging generation against the cap would mean an
 * account at its limit could not draft a replacement for a page it intends to overwrite.
 */
@RestController
public class CampaignPageGenerationController {

    private final CampaignPageGenerationService generation;
    private final RequestUserResolver requestUserResolver;

    public CampaignPageGenerationController(CampaignPageGenerationService generation,
                                            RequestUserResolver requestUserResolver) {
        this.generation = generation;
        this.requestUserResolver = requestUserResolver;
    }

    /**
     * Produce 2-3 page drafts from a campaign brief.
     *
     * <p>Always returns drafts on a 2xx — a generator failure is reported in the body's
     * {@code fallback} and {@code detail} fields with a usable template draft alongside, never as
     * an error status. See {@link CampaignPageGenerationService}.
     */
    @PostMapping("/api/campaign-pages/generate")
    public JsonNode generate(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestBody ObjectNode payload) {
        return generation.generate(tenantScoped(authorization, payload));
    }

    /**
     * Rewrite one section of a draft the user is editing.
     *
     * <p>Always 200 when the request itself is well-formed. A generator that cannot reword answers
     * {@code rewritten: false} with a reason, because "no suggestion" is an answer rather than a
     * failure — the user's own text is untouched either way.
     */
    @PostMapping("/api/campaign-pages/sections/rewrite")
    public JsonNode rewriteSection(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody ObjectNode payload) {
        return generation.rewriteSection(tenantScoped(authorization, payload));
    }

    /**
     * Produce one more draft, avoiding headlines the caller has already been shown.
     *
     * <p>Separate from {@code /generate} rather than a flag on it: this returns exactly one variant
     * to swap into a card, and takes the seen-headline list that only makes sense when there is
     * already a comparison on screen.
     */
    @PostMapping("/api/campaign-pages/variants/regenerate")
    public JsonNode regenerateVariant(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody ObjectNode payload) {
        return generation.regenerateVariant(tenantScoped(authorization, payload));
    }

    /**
     * Verify the caller and stamp the brand they actually hold onto the payload.
     *
     * <p><b>Overwrites any brandId the caller sent.</b> The service uses this value to read the
     * brand, campaign and creator records that enrich the brief, so a caller who could set it would
     * be able to pull another tenant's campaign names and creator handles into a page they own.
     * Taken from the verified token and written over whatever arrived, rather than validated —
     * there is no legitimate reason for a client to supply it at all.
     *
     * <p>Called before any generator runs, so an unauthenticated caller costs no model spend.
     */
    private ObjectNode tenantScoped(String authorization, ObjectNode payload) {
        UUID brandId = requestUserResolver.requireTenantContext(authorization).brandId();
        if (brandId != null) {
            payload.put("brandId", brandId.toString());
        } else {
            // An account-scoped role with no active brand can still generate — it just gets no
            // record enrichment. Removed rather than left, so a stale value cannot leak through.
            payload.remove("brandId");
        }
        return payload;
    }
}
