package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fills a campaign brief from the brand, campaign and creator records the product already holds
 * (roadmap PR-35).
 *
 * <p><b>The problem this solves.</b> The brief form asks the user to retype things the system
 * already knows: the brand's name, what the campaign is called and what type it is, which creator
 * is running it and on which platform. Every one of those is a field the user can get subtly wrong
 * — a misremembered handle, a campaign name that does not match the one on the coupon — and a
 * generated page built on the wrong details is worse than one built on none, because it looks
 * authoritative.
 *
 * <p><b>The user's own words always win.</b> Enrichment only fills fields the brief left blank. A
 * user who typed a specific audience meant that audience, and a system that overwrote it with a
 * record's value would be arguing with them. This is why every method below is "fill if absent"
 * rather than "set".
 *
 * <p><b>Never fails the request.</b> Each lookup is independent and best-effort: a campaign that
 * cannot be read produces a brief without campaign detail, not a failed generation. Generation with
 * a thinner brief is the degraded mode; an error page is not.
 *
 * <p><b>Reads only through published projections.</b> Campaign, creator and coupon data reach this
 * class as the DAO returns them through the shared gateway, not through another context's own
 * client — the boundary rule {@code BffContextBoundaryTest} enforces.
 */
@Component
public class BriefEnricher {

    private static final Logger log = LoggerFactory.getLogger(BriefEnricher.class);

    private final DaoGatewayClient dao;

    public BriefEnricher(DaoGatewayClient dao) {
        this.dao = dao;
    }

    /**
     * Return the payload with any blank fields filled from real records.
     *
     * <p>Mutates and returns the same node: the caller reads a brief out of it immediately after,
     * and copying would only invite the two to diverge.
     *
     * @param brandId    the verified tenant, never taken from the payload
     * @param payload    the brief as the user submitted it
     */
    public ObjectNode enrich(UUID brandId, ObjectNode payload) {
        // Recorded so the response can tell the user which details were filled in for them. A page
        // that quietly acquired a creator's name is a surprise; one that says where it came from is
        // a feature.
        Set<String> filled = new LinkedHashSet<>();

        enrichFromBrand(brandId, payload, filled);
        enrichFromCampaign(brandId, payload, filled);
        enrichFromCreator(brandId, payload, filled);

        if (!filled.isEmpty()) {
            payload.putArray("enrichedFields").addAll(
                    filled.stream().map(payload.arrayNode()::textNode).toList());
        }
        return payload;
    }

    // ---- brand ---------------------------------------------------------

    /**
     * The brand's own name and industry.
     *
     * <p>Fed to the prompt as context rather than as copy: a page that opens by naming the brand
     * reads like a press release, but a model that does not know whose page it is writes generic
     * copy that could belong to anyone.
     */
    private void enrichFromBrand(UUID brandId, ObjectNode payload, Set<String> filled) {
        JsonNode brand = read("/tenancy/brands/" + brandId, null);
        if (brand == null) {
            return;
        }
        if (fillIfBlank(payload, "brandName", text(brand, "name"))) {
            filled.add("brand name");
        }
        // The brand's own tone setting, when it has one, is a better default than asking every
        // campaign to restate it.
        if (fillIfBlank(payload, "brandTone", text(brand, "tone"))) {
            filled.add("brand tone");
        }
    }

    // ---- campaign ------------------------------------------------------

    /**
     * The campaign's name and type.
     *
     * <p>{@code campaignType} is the interesting one: the brief form offers a page-archetype
     * dropdown, but the campaign record already knows whether this is a launch or an always-on
     * affiliate push. Taking it from the record means the two cannot disagree.
     */
    private void enrichFromCampaign(UUID brandId, ObjectNode payload, Set<String> filled) {
        String campaignId = text(payload, "campaignId");
        if (campaignId == null) {
            return;
        }
        JsonNode campaign = read("/campaigns/" + campaignId, null);
        if (campaign == null || !belongsTo(campaign, brandId)) {
            // A campaign id from another brand is not an error to report back — reporting it would
            // confirm the id exists. It is simply not used.
            return;
        }
        if (fillIfBlank(payload, "campaignName", text(campaign, "name"))) {
            filled.add("campaign name");
        }
        if (fillIfBlank(payload, "campaignType", text(campaign, "campaignType"))) {
            filled.add("campaign type");
        }
        // The campaign brief, where one exists, is the richest source of goal and talking points —
        // it is the document the creator is already executing against, so a landing page built
        // from it says the same things the posts driving traffic to it say.
        enrichFromCampaignBrief(brandId, campaignId, payload, filled);
    }

    private void enrichFromCampaignBrief(UUID brandId, String campaignId, ObjectNode payload, Set<String> filled) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", brandId.toString());
        query.put("campaignId", campaignId);
        JsonNode briefs = read("/campaign-briefs", query);
        if (briefs == null || !briefs.isArray() || briefs.isEmpty()) {
            return;
        }
        JsonNode content = briefs.get(0).path("content");
        if (fillIfBlank(payload, "goal", firstNonBlank(text(content, "goals"), text(content, "summary")))) {
            filled.add("goal");
        }
        if (fillIfBlank(payload, "proofPointsText", text(content, "talkingPoints"))) {
            filled.add("talking points");
        }
        // The FTC/ASA disclosure is a legal requirement on the page, not a stylistic choice, so it
        // is carried through when the brief defines one.
        if (fillIfBlank(payload, "disclosure", text(briefs.get(0), "disclosureText"))) {
            filled.add("disclosure");
        }
    }

    // ---- creator -------------------------------------------------------

    /**
     * The creator's real name, handle and platform.
     *
     * <p>Resolved from a {@code creatorId} when the caller has one. The brief's free-text
     * {@code creatorHandle} stays supported for the case where no creator record exists yet, but a
     * real id is strictly better: it cannot be a typo, and it carries the platform, which changes
     * how the page should read (a TikTok audience arrives differently from an email list).
     */
    private void enrichFromCreator(UUID brandId, ObjectNode payload, Set<String> filled) {
        String creatorId = text(payload, "creatorId");
        if (creatorId == null) {
            return;
        }
        JsonNode creator = read("/creators/" + creatorId, null);
        if (creator == null || !belongsTo(creator, brandId)) {
            return;
        }
        String handle = text(creator, "handle");
        if (handle != null && fillIfBlank(payload, "creatorHandle",
                handle.startsWith("@") ? handle : "@" + handle)) {
            filled.add("creator handle");
        }
        if (fillIfBlank(payload, "creatorName", text(creator, "name"))) {
            filled.add("creator name");
        }
        if (fillIfBlank(payload, "creatorPlatform", text(creator, "platform"))) {
            filled.add("creator platform");
        }
    }

    // ---- helpers -------------------------------------------------------

    /**
     * Every read is best-effort.
     *
     * <p>Returns null on any failure rather than propagating: enrichment is an improvement to the
     * brief, and an unavailable record must degrade the page's detail, never the request.
     */
    private JsonNode read(String path, Map<String, String> query) {
        try {
            return dao.get(path, query);
        } catch (RuntimeException e) {
            log.info("Brief enrichment could not read {}: {}", path, e.toString());
            return null;
        }
    }

    /** Records are only used when they belong to the verified tenant. */
    private boolean belongsTo(JsonNode record, UUID brandId) {
        return record.hasNonNull("brandId")
                && record.get("brandId").asText().equals(brandId.toString());
    }

    /** Sets the field only when the user left it blank. Returns whether it was set. */
    private boolean fillIfBlank(ObjectNode payload, String field, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String existing = text(payload, field);
        if (existing != null) {
            return false;
        }
        payload.put(field, value.trim());
        return true;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String out = value.asText();
        return out == null || out.isBlank() ? null : out;
    }
}
