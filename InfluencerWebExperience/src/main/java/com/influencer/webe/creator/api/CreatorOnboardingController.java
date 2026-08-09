package com.influencer.webe.creator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.creator.application.CreatorOnboardingService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creator onboarding — handle resolution and lead capture (roadmap Phase C).
 *
 * <p>Two audiences, deliberately separated:
 * <ul>
 *   <li><b>Brand-authenticated</b> — a brand user pastes a handle and sees what would be
 *       captured, then adds the creator. Requires {@code creator:write}.</li>
 *   <li><b>Public</b> — the signup block on a published landing page. No token, because the
 *       creator filling it in does not have one. See {@link #publicSignup} for what that
 *       costs and how it is contained.</li>
 * </ul>
 */
@RestController
public class CreatorOnboardingController {

    private final CreatorOnboardingService onboarding;
    private final RequestUserResolver requestUserResolver;
    private final DaoGatewayClient dao;

    public CreatorOnboardingController(CreatorOnboardingService onboarding,
                                       RequestUserResolver requestUserResolver,
                                       DaoGatewayClient dao) {
        this.onboarding = onboarding;
        this.requestUserResolver = requestUserResolver;
        this.dao = dao;
    }

    /** Preview what a handle resolves to. Reads only — nothing is persisted (C.2). */
    @PostMapping("/api/creators/resolve-handle")
    public JsonNode resolveHandle(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody ObjectNode payload) {
        requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return onboarding.resolveHandle(
                payload.path("platform").asText(null),
                payload.path("handle").asText(null));
    }

    /** Brand-side lead capture: add a creator by handle, enriched (C.5). */
    @PostMapping("/api/creators/capture-lead")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode captureLead(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CREATOR_WRITE);
        return onboarding.captureLead(context.brandId(), context.userId(), payload);
    }

    /**
     * Creator signup from a published landing page (C.5) — <b>no authentication</b>.
     *
     * <p>A creator applying to a campaign has no account, so this cannot require a token. Three
     * things contain what that opens up:
     *
     * <ol>
     *   <li><b>The brand is derived from the page slug, never from the request.</b> A body that
     *       could name its own {@code brandId} would let anyone inject a lead into any brand's
     *       CRM. The slug is looked up and the owning brand is read off the row.</li>
     *   <li><b>Only a published page accepts signups.</b> A draft page is not a live campaign,
     *       and its slug being guessable must not make it an open ingest endpoint.</li>
     *   <li><b>The lead is created as {@code status = lead}</b>, never approved. Everything
     *       downstream still requires a brand decision — this endpoint cannot grant access to
     *       briefs, assets or money.</li>
     * </ol>
     *
     * <p>Not solved here: rate limiting. Nothing stops a script filling this in repeatedly, and
     * a bulk-signup flood would land in a brand's review queue. That needs infrastructure the
     * platform does not have yet and is recorded as a known gap rather than papered over with
     * a check that would not survive contact with a real bot.
     */
    @PostMapping("/api/public/landing/{slug}/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode publicSignup(@PathVariable String slug, @RequestBody ObjectNode payload) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("publicSlug", slug);
        JsonNode templates = dao.get("/landing-templates", query);
        if (templates == null || !templates.isArray() || templates.size() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        JsonNode template = templates.get(0);

        if (!"published".equalsIgnoreCase(template.path("status").asText("draft"))) {
            // Same rule as the public renderer: an unpublished page is not addressable.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }

        UUID brandId = UUID.fromString(template.get("brandId").asText());

        // Rebuild the payload rather than forwarding it: the caller supplies handle, platform,
        // name and email and nothing else. Anything else in the body — brandId, status,
        // followerCount, a vetting decision — is dropped here rather than trusted.
        ObjectNode safe = payload.objectNode();
        safe.put("handle", payload.path("handle").asText(""));
        safe.put("platform", payload.path("platform").asText("instagram"));
        if (payload.hasNonNull("name")) {
            safe.put("name", payload.get("name").asText());
        }
        if (payload.hasNonNull("email")) {
            safe.put("email", payload.get("email").asText());
        }
        safe.put("leadSource", "landing_page");
        safe.put("landingTemplateId", template.get("id").asText());

        // createdByUserId is null: no user performed this action, and attributing it to the
        // page's owner would misreport who added the creator.
        return onboarding.captureLead(brandId, null, safe);
    }
}
