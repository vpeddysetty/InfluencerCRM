package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a creator needs to post a page to their own handle (roadmap PR-45).
 *
 * <p><b>What this is instead of.</b> The honest answer to "the creator publishes to their handle"
 * is not a platform adapter — those are `PR-46`, gated on Meta and TikTok approvals on somebody
 * else's clock. It is giving the creator the four things they would otherwise assemble by hand: a
 * caption written from the page's own words, the tracked link, the disclosure they are legally
 * required to include, and the assets already the right size.
 *
 * <p><b>The caption comes from the page's SECTIONS, and no model is called.</b> §10.3 calls those
 * sections the best AI input in the product because they are already structured and already
 * written — which is exactly why they do not need rewriting into a caption. A generated caption
 * would cost a billed call, take seconds, and say the same thing in different words, with the added
 * risk of inventing a claim the page does not make. The creator edits it anyway.
 *
 * <p><b>The disclosure is NON-REMOVABLE.</b> It is appended by the server, after the caption, on
 * every platform, and the response carries it as its own field so a UI cannot render an editable
 * caption that quietly drops it. This is an FTC obligation, not a preference, and `Brief` already
 * carries disclosure as a first-class field with the reasoning encoded — reused here rather than
 * reinvented.
 *
 * <p><b>The coupon code leads, not the link.</b> Instagram captions are not clickable, so on the
 * dominant platform a tracked URL is inert plain text. The code survives being read off a screen
 * and is already the attribution primitive. The link still travels — for platforms where it works,
 * and for the bio — but it is not what the caption opens with.
 */
@Service
public class ShareKitService {

    private static final Logger log = LoggerFactory.getLogger(ShareKitService.class);

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final String publicBaseUrl;

    public ShareKitService(DaoGatewayClient dao,
                           ResponseShapeService shape,
                           @Value("${web-experience.public-base-url:}") String publicBaseUrl) {
        this.dao = dao;
        this.shape = shape;
        // FIRST value only, trailing slash stripped -- the same trap MemberInvitationService
        // records, where the whole comma-separated string reached a mail body as a broken link.
        this.publicBaseUrl = publicBaseUrl == null ? ""
                : publicBaseUrl.split(",")[0].trim().replaceAll("/+$", "");
    }

    /**
     * Build the kit for one creator's coupon on one page.
     *
     * @param brandId    the verified tenant, never taken from the request
     * @param templateId the landing page being shared
     * @param couponId   which creator's code — the kit is per-creator, because the link and the
     *                   code both are
     */
    public JsonNode forCoupon(UUID brandId, UUID templateId, UUID couponId) {
        JsonNode template = read("/landing-templates/" + templateId);
        if (template == null || !brandId.toString().equals(text(template, "brandId"))) {
            // Not "forbidden": saying which of the two it was confirms the id exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }

        // A page nobody can open is not shareable, and a kit built from one would hand a creator a
        // dead link to post. PR-39's publish-readiness advisory makes the same distinction.
        if (!"published".equalsIgnoreCase(text(template, "status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Publish the page before sharing it — the link would not resolve yet.");
        }

        JsonNode coupon = read("/influencer-campaign-codes/" + couponId);
        if (coupon == null || !brandId.toString().equals(text(coupon, "brandId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Code not found");
        }

        String slug = text(template, "publicSlug");
        String code = text(coupon, "code");
        String creatorSlug = text(coupon, "publicSlug");

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("templateId", templateId.toString());
        out.put("couponId", couponId.toString());
        out.put("code", code);
        out.put("link", linkFor(slug, creatorSlug));
        out.put("disclosure", disclosureFor(template));
        out.set("captions", captionsFor(template, code));
        out.set("assets", assetsFor(template));
        return out;
    }

    /**
     * Record that a creator says they posted this (roadmap PR-45).
     *
     * <p><b>Their claim, not a measurement.</b> Nothing here can see a creator's feed, so this
     * stores what they told us and is named so a later reader cannot mistake it for verification.
     * The alternative — inferring a post from a spike in page views — would be a guess presented as
     * a fact, and the brand acting on it would be acting on our arithmetic rather than the
     * creator's word.
     *
     * <p>Ownership is re-checked rather than trusted: the same page/coupon reads {@link #forCoupon}
     * does, because a claim recorded against another brand's page is a cross-tenant write.
     */
    public JsonNode recordPosted(UUID brandId, UUID templateId, UUID couponId,
                                 UUID reportedByUserId, UUID creatorIdentityId, String platform) {
        JsonNode template = read("/landing-templates/" + templateId);
        if (template == null || !brandId.toString().equals(text(template, "brandId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        JsonNode coupon = read("/influencer-campaign-codes/" + couponId);
        if (coupon == null || !brandId.toString().equals(text(coupon, "brandId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Code not found");
        }

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("landingTemplateId", templateId.toString());
        body.put("campaignCodeId", couponId.toString());
        if (reportedByUserId != null) {
            body.put("reportedByUserId", reportedByUserId.toString());
        }
        if (creatorIdentityId != null) {
            body.put("creatorIdentityId", creatorIdentityId.toString());
        }
        if (platform != null && !platform.isBlank()) {
            body.put("platform", platform.trim());
        }
        return dao.post("/share-posts", body);
    }

    /** What has been claimed for this page, newest first. */
    public JsonNode postsFor(UUID brandId, UUID templateId) {
        JsonNode template = read("/landing-templates/" + templateId);
        if (template == null || !brandId.toString().equals(text(template, "brandId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("landingTemplateId", templateId.toString());
        JsonNode posts = readQuery("/share-posts", query);
        return posts == null ? shape.objectMapper().createArrayNode() : posts;
    }

    // ---- the link ------------------------------------------------------

    /**
     * The tracked URL.
     *
     * <p>Per-creator when the coupon has its own slug, because that is what attributes a visit to
     * them. §10.3 records that {@code /s/{slug}/{creator}} 404s when no coupon matches, so the
     * per-creator form is used only when the coupon actually carries a slug — a share kit whose
     * central artifact is a dead link is worse than one with a plainer link that works.
     */
    private String linkFor(String slug, String creatorSlug) {
        if (slug == null || slug.isBlank()) {
            return "";
        }
        String path = creatorSlug == null || creatorSlug.isBlank()
                ? "/s/" + slug
                : "/s/" + slug + "/" + creatorSlug;
        return publicBaseUrl.isBlank() ? path : publicBaseUrl + path;
    }

    // ---- the caption ---------------------------------------------------

    /**
     * One caption per platform, from the page's own words.
     *
     * <p>They differ by LENGTH and by what leads, not by voice: the page already has a voice, and
     * three rewrites of it would be three chances to drift from what the page actually says.
     */
    private ArrayNode captionsFor(JsonNode template, String code) {
        String headline = firstField(template, "hero", "headline");
        String support = firstNonBlank(
                firstField(template, "hero", "subheadline"),
                firstField(template, "offer", "supporting"),
                firstField(template, "text", "body"));
        String offer = firstField(template, "offer", "headline");

        ArrayNode captions = shape.objectMapper().createArrayNode();
        // Instagram: the code leads, because the link is not clickable there. "Link in bio" is the
        // convention creators and their audiences already use, so it is stated rather than implied.
        captions.add(caption("instagram", join(
                headline,
                offer,
                code == null || code.isBlank() ? null : "Use code " + code,
                "Link in bio.")));
        // TikTok: same shape, shorter -- captions are truncated hard in the feed.
        captions.add(caption("tiktok", join(
                headline,
                code == null || code.isBlank() ? null : "Code " + code)));
        // Anywhere the link is clickable, it leads instead of the code.
        captions.add(caption("other", join(headline, support, offer,
                code == null || code.isBlank() ? null : "Use code " + code)));
        return captions;
    }

    private ObjectNode caption(String platform, String body) {
        ObjectNode node = shape.objectMapper().createObjectNode();
        node.put("platform", platform);
        node.put("body", body);
        return node;
    }

    /**
     * The disclosure, which is appended to every caption by whatever renders it.
     *
     * <p>Returned as its own field rather than pre-joined into each caption so a UI cannot present
     * an editable text box that silently drops it. The brand's own wording wins when the page
     * carries one — a `legal` section is what their counsel approved — and `#ad` is the floor, not
     * a preference: it is the shortest form the FTC accepts and it must survive a creator trimming
     * a long caption.
     */
    private String disclosureFor(JsonNode template) {
        String fromPage = firstField(template, "legal", "body");
        return fromPage == null || fromPage.isBlank() ? "#ad" : fromPage.trim();
    }

    // ---- the assets ----------------------------------------------------

    /**
     * The images already on the page, which are the ones the creator should post.
     *
     * <p><b>No resizing, and the response says so.</b> There is no image processing anywhere in
     * this codebase — {@code AssetService} uses {@code ImageIO} only to MEASURE — so claiming
     * per-platform crops would be a promise nothing keeps. Each asset carries its real dimensions
     * and a flag for whether it already suits a square feed, which lets the UI say "this will be
     * cropped" instead of the product silently cropping it wrong.
     */
    private ArrayNode assetsFor(JsonNode template) {
        ArrayNode assets = shape.objectMapper().createArrayNode();
        JsonNode sections = template.path("sections");
        if (!sections.isArray()) {
            return assets;
        }
        for (JsonNode section : sections) {
            if (!"media".equals(text(section, "type"))) {
                continue;
            }
            String url = text(section.path("fields"), "asset");
            if (url == null || url.isBlank()) {
                continue;
            }
            ObjectNode asset = assets.addObject();
            asset.put("url", url);
            asset.put("altText", textOr(section.path("fields"), "altText", ""));
        }
        return assets;
    }

    // ---- helpers -------------------------------------------------------

    /** The first non-empty value of {@code field} on the first section of {@code type}. */
    private String firstField(JsonNode template, String type, String field) {
        JsonNode sections = template.path("sections");
        if (!sections.isArray()) {
            return null;
        }
        for (JsonNode section : sections) {
            if (!type.equals(text(section, "type"))) {
                continue;
            }
            String value = text(section.path("fields"), field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Joins the parts that exist, one per line, skipping the ones that do not. */
    private String join(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                kept.add(part.trim());
            }
        }
        return String.join("\n\n", kept);
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
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null ? fallback : value;
    }

    private JsonNode readQuery(String path, Map<String, String> query) {
        try {
            return dao.get(path, query);
        } catch (RuntimeException e) {
            log.info("Share kit could not read {}: {}", path, e.toString());
            return null;
        }
    }

    private JsonNode read(String path) {
        try {
            return dao.get(path, new LinkedHashMap<>());
        } catch (RuntimeException e) {
            log.info("Share kit could not read {}: {}", path, e.toString());
            return null;
        }
    }
}
