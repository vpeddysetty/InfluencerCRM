package com.influencer.webe.content.application;

import com.influencer.webe.shared.application.ResponseShapeService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Landing template management + public hosted page rendering (Content Phase 2).
 *
 * A landing template is an ordered list of typed blocks owned by a campaign. Each
 * coupon on that campaign resolves to a personalized page at
 * {@code /s/{templateSlug}/{creatorSlug}} — the template rendered with the
 * creator's identity + their coupon code. Rendering is server-side and every
 * dynamic value is HTML-escaped (never store-and-echo raw HTML) — the public page
 * is an XSS / brand-safety surface.
 */
@Service
public class LandingService {
    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public LandingService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    /**
     * Create or update the campaign's landing template. One per campaign; a stable
     * public_slug is generated on first create. On save, (re)assigns a public_slug
     * to each of the campaign's coupons so their landing pages resolve.
     */
    public JsonNode saveTemplate(UUID brandId, ObjectNode payload) {
        UUID campaignId = uuid(payload, "campaignId");
        if (campaignId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId is required");
        }

        // Look up an existing template for this campaign.
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        q.put("campaignId", campaignId.toString());
        JsonNode existingList = dao.get("/landing-templates", q);
        JsonNode existing = existingList != null && existingList.isArray() && existingList.size() > 0
                ? existingList.get(0) : null;

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("campaignId", campaignId.toString());
        body.put("name", textOr(payload, "name", "Landing page"));
        body.put("status", textOr(payload, "status", "draft"));
        stringifyJsonb(payload, body, "blocks", "theme");

        JsonNode saved;
        if (existing != null) {
            body.put("publicSlug", existing.get("publicSlug").asText());
            saved = dao.put("/landing-templates/" + existing.get("id").asText(), body);
        } else {
            body.put("publicSlug", generateSlug(campaignId));
            saved = dao.post("/landing-templates", body);
        }

        // Assign a per-coupon public_slug to every coupon on this campaign.
        assignCouponSlugs(brandId, campaignId, saved.get("publicSlug").asText());
        return shape.landingTemplate(saved);
    }

    /**
     * Render the personalized public landing page for a template slug + creator
     * slug. Records a landing_page_view (click attribution). Returns sanitized HTML.
     */
    public String renderPublic(String templateSlug, String creatorSlug, String referrer, String userAgent) {
        Map<String, String> tq = new LinkedHashMap<>();
        tq.put("publicSlug", templateSlug);
        JsonNode templates = dao.get("/landing-templates", tq);
        if (templates == null || !templates.isArray() || templates.size() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        JsonNode template = templates.get(0);
        UUID brandId = UUID.fromString(template.get("brandId").asText());
        UUID campaignId = UUID.fromString(template.get("campaignId").asText());

        // Find the coupon for this creator slug on the campaign.
        JsonNode coupon = resolveCoupon(brandId, campaignId, creatorSlug);
        if (coupon == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No coupon for this creator");
        }

        // Record the landing view (click funnel step) — best effort.
        recordView(brandId, coupon.get("id").asText(), referrer, userAgent);

        Map<String, String> tokens = buildTokens(brandId, coupon);
        return renderHtml(template, coupon, tokens);
    }

    /**
     * Render a preview of a landing template WITHOUT persisting it and WITHOUT
     * recording a landing_page_view. Renders the caller's current (possibly
     * unsaved) blocks, personalized for a chosen coupon — or a synthetic sample
     * coupon when none is picked. Content preview (brand-only).
     *
     * Payload: { campaignId, name, blocks:[...], couponId? }
     */
    public String previewTemplate(UUID brandId, ObjectNode payload) {
        JsonNode coupon = resolvePreviewCoupon(brandId, payload);

        ObjectNode template = shape.objectMapper().createObjectNode();
        template.put("name", textOr(payload, "name", "Landing page"));
        JsonNode blocks = payload.get("blocks");
        template.set("blocks", blocks != null && blocks.isArray() ? blocks : shape.objectMapper().createArrayNode());

        Map<String, String> tokens = buildTokens(brandId, coupon);
        return renderHtml(template, coupon, tokens);
    }

    /**
     * Pick the coupon to personalize a preview with: the requested couponId (if it
     * belongs to this user), else the first coupon on the campaign, else a
     * synthetic sample so the preview always renders.
     */
    private JsonNode resolvePreviewCoupon(UUID brandId, ObjectNode payload) {
        UUID couponId = uuid(payload, "couponId");
        if (couponId != null) {
            JsonNode c = dao.get("/influencer-campaign-codes/" + couponId, null);
            if (c != null && c.hasNonNull("brandId") && c.get("brandId").asText().equals(brandId.toString())) {
                return c;
            }
        }
        UUID campaignId = uuid(payload, "campaignId");
        if (campaignId != null) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("brandId", brandId.toString());
            q.put("campaignId", campaignId.toString());
            JsonNode coupons = dao.get("/influencer-campaign-codes", q);
            if (coupons != null && coupons.isArray() && coupons.size() > 0) {
                return coupons.get(0);
            }
        }
        // Synthetic sample coupon so the preview is never empty.
        ObjectNode sample = shape.objectMapper().createObjectNode();
        sample.put("code", "SAMPLE20");
        sample.put("discountType", "percent");
        sample.put("discountValue", "20");
        sample.put("landingUrl", "#");
        return sample;
    }

    // ---- token / render ------------------------------------------------

    private Map<String, String> buildTokens(UUID brandId, JsonNode coupon) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("coupon.code", text(coupon, "code"));
        tokens.put("discount", describeDiscount(coupon));
        tokens.put("channel", textOrDefault(coupon, "channel", ""));

        // Resolve the creator name.
        String creatorName = "our creator";
        if (coupon.hasNonNull("creatorId")) {
            JsonNode creator = dao.get("/creators/" + coupon.get("creatorId").asText(), null);
            if (creator != null && creator.hasNonNull("name")) {
                creatorName = creator.get("name").asText();
            } else if (creator != null && creator.hasNonNull("handle")) {
                creatorName = creator.get("handle").asText();
            }
        }
        tokens.put("creator.name", creatorName);
        return tokens;
    }

    private String renderHtml(JsonNode template, JsonNode coupon, Map<String, String> tokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>").append(esc(text(template, "name"))).append("</title>");
        sb.append("<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;color:#0f172a}"
                + ".wrap{max-width:640px;margin:0 auto;padding:24px}.hero{font-size:2rem;font-weight:700;margin:.5rem 0}"
                + ".code{display:inline-block;font-weight:700;letter-spacing:.05em;background:#eef2ff;color:#4338ca;"
                + "padding:.5rem .9rem;border-radius:10px;font-size:1.25rem}.cta{display:inline-block;margin-top:1rem;"
                + "background:#4f46e5;color:#fff;text-decoration:none;padding:.7rem 1.2rem;border-radius:10px}"
                + ".legal{color:#64748b;font-size:.8rem;margin-top:2rem}.blurb{font-style:italic;color:#334155}"
                + "img{max-width:100%;border-radius:12px}</style></head><body><div class=\"wrap\">");

        JsonNode blocks = parseArray(template.get("blocks"));
        for (JsonNode block : blocks) {
            sb.append(renderBlock(block, coupon, tokens));
        }

        // Creator personalization blurb (Content Phase 3 — rendered when approved).
        String blurb = text(coupon, "personalBlurb");
        String pStatus = textOrDefault(coupon, "personalizationStatus", "none");
        if (blurb != null && !blurb.isBlank() && "approved".equalsIgnoreCase(pStatus)) {
            sb.append("<p class=\"blurb\">").append(esc(blurb)).append("</p>");
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    private String renderBlock(JsonNode block, JsonNode coupon, Map<String, String> tokens) {
        String type = textOrDefault(block, "type", "richText");
        switch (type) {
            case "hero":
                return "<h1 class=\"hero\">" + esc(fill(textOrDefault(block, "text", ""), tokens)) + "</h1>";
            case "image": {
                String url = textOrDefault(block, "url", "");
                return url.isBlank() ? "" : "<img src=\"" + escAttr(url) + "\" alt=\"\">";
            }
            case "couponBlock":
                return "<p>Use code <span class=\"code\">" + esc(textOrDefault(coupon, "code", "")) + "</span>"
                        + " for " + esc(describeDiscount(coupon)) + "</p>";
            case "productCta": {
                String label = textOrDefault(block, "label", "Shop now");
                String href = textOrDefault(coupon, "landingUrl", "#");
                return "<a class=\"cta\" href=\"" + escAttr(href) + "\">" + esc(fill(label, tokens)) + "</a>";
            }
            case "legal":
                return "<p class=\"legal\">" + esc(fill(textOrDefault(block, "text", ""), tokens)) + "</p>";
            case "richText":
            default:
                return "<p>" + esc(fill(textOrDefault(block, "text", ""), tokens)) + "</p>";
        }
    }

    /** Replace {{token}} occurrences with escaped values (values escaped at render time). */
    private String fill(String template, Map<String, String> tokens) {
        if (template == null) {
            return "";
        }
        String out = template;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------

    private void assignCouponSlugs(UUID brandId, UUID campaignId, String templateSlug) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        q.put("campaignId", campaignId.toString());
        JsonNode coupons = dao.get("/influencer-campaign-codes", q);
        if (coupons == null || !coupons.isArray()) {
            return;
        }
        for (JsonNode c : coupons) {
            if (c.hasNonNull("publicSlug") && !c.get("publicSlug").asText().isBlank()) {
                continue; // already has a slug
            }
            String creatorSlug = slugForCoupon(c);
            ObjectNode update = c.deepCopy();
            update.put("publicSlug", creatorSlug);
            try {
                dao.put("/influencer-campaign-codes/" + c.get("id").asText(), update);
            } catch (RuntimeException ignored) {
                // best-effort; a slug collision or transient error shouldn't fail template save
            }
        }
    }

    private JsonNode resolveCoupon(UUID brandId, UUID campaignId, String creatorSlug) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        q.put("campaignId", campaignId.toString());
        JsonNode coupons = dao.get("/influencer-campaign-codes", q);
        if (coupons == null || !coupons.isArray()) {
            return null;
        }
        for (JsonNode c : coupons) {
            if (c.hasNonNull("publicSlug") && c.get("publicSlug").asText().equalsIgnoreCase(creatorSlug)) {
                return c;
            }
        }
        return null;
    }

    private void recordView(UUID brandId, String couponId, String referrer, String userAgent) {
        try {
            ObjectNode view = shape.objectMapper().createObjectNode();
            view.put("brandId", brandId.toString());
            view.put("campaignCodeId", couponId);
            if (referrer != null) view.put("referrer", referrer);
            if (userAgent != null) view.put("userAgent", userAgent);
            dao.post("/landing-page-views", view);
        } catch (RuntimeException ignored) {
            // view recording is best-effort; never block the page render
        }
    }

    private String slugForCoupon(JsonNode coupon) {
        String base = coupon.hasNonNull("code") ? coupon.get("code").asText() : coupon.get("id").asText();
        return base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-");
    }

    private String generateSlug(UUID campaignId) {
        return "c-" + campaignId.toString().substring(0, 8);
    }

    private String describeDiscount(JsonNode coupon) {
        String type = text(coupon, "discountType");
        String value = text(coupon, "discountValue");
        if (type == null) {
            return "an exclusive deal";
        }
        switch (type) {
            case "percent": return (value == null ? "" : trimNum(value) + "% ") + "off";
            case "fixed": return "$" + (value == null ? "" : trimNum(value)) + " off";
            case "free_shipping": return "free shipping";
            case "bogo": return "buy one get one";
            default: return type;
        }
    }

    private String trimNum(String v) {
        try {
            BigDecimal d = new BigDecimal(v);
            return d.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private void stringifyJsonb(ObjectNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            JsonNode node = from.get(field);
            if (node != null && (node.isObject() || node.isArray())) {
                try {
                    to.put(field, shape.objectMapper().writeValueAsString(node));
                } catch (Exception ignored) {
                    // leave unset; DAO defaults apply
                }
            }
        }
    }

    private JsonNode parseArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return shape.objectMapper().createArrayNode();
        }
        if (node.isArray()) {
            return node;
        }
        if (node.isTextual()) {
            try {
                JsonNode parsed = shape.objectMapper().readTree(node.asText());
                return parsed.isArray() ? parsed : shape.objectMapper().createArrayNode();
            } catch (Exception e) {
                return shape.objectMapper().createArrayNode();
            }
        }
        return shape.objectMapper().createArrayNode();
    }

    private UUID uuid(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field) || payload.get(field).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(field).asText());
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v == null || v.isBlank() ? fallback : v;
    }

    private String textOrDefault(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v == null ? fallback : v;
    }

    /** HTML-escape text content. */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Escape for an HTML attribute (drop javascript: and other non-http(s) schemes for URLs). */
    private String escAttr(String s) {
        if (s == null) return "";
        String lower = s.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
            return "#";
        }
        return esc(s);
    }
}
