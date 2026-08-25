package com.influencer.webe.content.application;

import com.influencer.webe.shared.application.PlatformMetrics;
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
    private final LandingDocumentSanitizer sanitizer;
    /** Phase E: free hosting is time-limited, so rendering has to check the window. */
    private final BrandDomainService domains;
    /** Phase H: render outcomes are the first thing to watch on a public surface. */
    private final PlatformMetrics metrics;

    public LandingService(DaoGatewayClient dao,
                          ResponseShapeService shape,
                          LandingDocumentSanitizer sanitizer,
                          BrandDomainService domains,
                          PlatformMetrics metrics) {
        this.dao = dao;
        this.shape = shape;
        this.sanitizer = sanitizer;
        this.domains = domains;
        this.metrics = metrics;
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
        // `document` is the GrapesJS output; omitted by the legacy block editor, which is
        // exactly why it must not be defaulted here — see the column comment.
        stringifyJsonb(payload, body, "blocks", "theme", "document");
        // A pending scheduled publish must survive an ordinary save. The DAO's PUT replaces the
        // row and does not null-guard this column (clearing it is how the scheduler consumes a
        // fired publish), so omitting it here would let any builder edit silently cancel a launch
        // the user had scheduled — with no error and nothing on screen to notice.
        if (existing != null && existing.hasNonNull("scheduledPublishAt")) {
            body.put("scheduledPublishAt", existing.get("scheduledPublishAt").asText());
        }
        String stage = textOr(payload, "stage", existing != null ? textOr(existing, "stage", "draft") : "draft");
        requireValidStage(stage);
        body.put("stage", stage);

        JsonNode saved;
        if (existing != null) {
            body.put("publicSlug", existing.get("publicSlug").asText());
            saved = dao.put("/landing-templates/" + existing.get("id").asText(), body);
        } else {
            body.put("publicSlug", generateSlug(campaignId));
            saved = dao.post("/landing-templates", body);
        }

        // Snapshot AFTER the write succeeds: a version for a save that failed would be a
        // record of something that never existed. Best-effort — history is valuable but
        // never worth failing the user's save for.
        snapshotVersion(brandId, saved);

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
        requireWithinHostingWindow(template);
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
        metrics.pageRendered("served");
        return renderHtml(template, coupon, tokens);
    }

    /**
     * Render the brand's own landing page at {@code /s/{slug}} — no creator, no coupon.
     *
     * <p><b>Why this exists.</b> Before Phase A the only public route was
     * {@code /s/{slug}/{creator}}, which resolves a coupon and 404s when none matches. A
     * brand could therefore build and save a page and have no way to view it — the page
     * was unreachable until a creator coupon existed. That made "build a page and publish
     * it" impossible on its own, so the builder needed a coupon-free path to be usable.
     *
     * <p>Tokens that describe a creator or a coupon have no value here and are rendered as
     * neutral placeholders rather than left as raw {@code {{coupon.code}}} braces on a
     * public page.
     *
     * <p>Unpublished pages are refused: {@code status} must be {@code published}. A draft
     * being publicly readable purely because its slug is guessable would make the
     * draft/published distinction meaningless.
     */
    public String renderPublicBrandPage(String templateSlug, String referrer, String userAgent) {
        Map<String, String> tq = new LinkedHashMap<>();
        tq.put("publicSlug", templateSlug);
        JsonNode templates = dao.get("/landing-templates", tq);
        if (templates == null || !templates.isArray() || templates.size() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        JsonNode template = templates.get(0);

        if (!"published".equalsIgnoreCase(textOrDefault(template, "status", "draft"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }

        // Phase E, decision #11: free hosting runs out. 410 Gone with a clear message rather
        // than a 404 that looks like a bug or a page that silently keeps serving. The row, its
        // blocks and its assets all stay — expiry unpublishes, it never deletes.
        requireWithinHostingWindow(template);

        UUID brandId = UUID.fromString(template.get("brandId").asText());
        recordBrandView(brandId, referrer, userAgent);

        // A neutral stand-in so a brand page never shows a creator's name or a coupon code
        // it does not have. Not a real coupon: nothing here is attributable.
        ObjectNode placeholder = shape.objectMapper().createObjectNode();
        placeholder.put("landingUrl", "#");

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("coupon.code", "");
        tokens.put("discount", "our latest offer");
        tokens.put("channel", "");
        tokens.put("creator.name", "our creators");

        metrics.pageRendered("served");
        return renderHtml(template, placeholder, tokens);
    }

    /**
     * Refuse to serve a page whose free hosting has lapsed (Phase E, decision #11).
     *
     * <p>410 Gone, not 404: the page existed and may exist again once hosting is renewed, and a
     * visitor (or a search engine) should be able to tell those apart. The stored page is
     * untouched — re-publishing after payment is a stage change, not a rebuild. Deleting a
     * brand's work because a trial lapsed is the kind of thing that ends a customer
     * relationship permanently.
     */
    private void requireWithinHostingWindow(JsonNode template) {
        if (domains.isExpired(template)) {
            metrics.pageRendered("expired");
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This page is no longer hosted. Its free hosting period has ended — "
                            + "the content is safe and it can be republished once hosting is renewed.");
        }
    }

    /**
     * Record a brand-page view.
     *
     * <p>{@code landing_page_views.campaign_code_id} is the coupon, and a brand page has
     * none — so the view is recorded without one. Nothing is invented to fill the column:
     * a fabricated coupon id would corrupt coupon-level funnel reporting, which is the
     * one thing that table is for.
     */
    private void recordBrandView(UUID brandId, String referrer, String userAgent) {
        try {
            ObjectNode view = shape.objectMapper().createObjectNode();
            view.put("brandId", brandId.toString());
            if (referrer != null) view.put("referrer", referrer);
            if (userAgent != null) view.put("userAgent", userAgent);
            dao.post("/landing-page-views", view);
        } catch (RuntimeException ignored) {
            // best-effort; never block the page render
        }
    }

    /**
     * Render a preview of a landing template WITHOUT persisting it and WITHOUT
     * recording a landing_page_view. Renders the caller's current (possibly
     * unsaved) blocks, personalized for a chosen coupon — or a synthetic sample
     * coupon when none is picked. Content preview (brand-only).
     *
     * Payload: { campaignId, name, blocks:[...], document:{html,css}, couponId? }
     */
    public String previewTemplate(UUID brandId, ObjectNode payload) {
        JsonNode coupon = resolvePreviewCoupon(brandId, payload);

        ObjectNode template = shape.objectMapper().createObjectNode();
        template.put("name", textOr(payload, "name", "Landing page"));
        JsonNode blocks = payload.get("blocks");
        template.set("blocks", blocks != null && blocks.isArray() ? blocks : shape.objectMapper().createArrayNode());
        // Preview the unsaved builder document when the caller sends one. Without this the
        // builder's live preview would show the last SAVED state, which is worse than no
        // preview — it looks like the edit was lost.
        JsonNode document = payload.get("document");
        if (document != null && !document.isNull()) {
            template.set("document", document);
        }

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

    /**
     * Render a page.
     *
     * <p>Two paths, chosen by whether the visual builder has ever written a document:
     * <ul>
     *   <li><b>Builder path</b> — sanitized GrapesJS HTML/CSS. Tokens are substituted into
     *       the HTML with escaped values, so a coupon code containing markup cannot inject.</li>
     *   <li><b>Legacy path</b> — the original typed-block renderer, unchanged.</li>
     * </ul>
     * The fallback is what lets Phase A ship without migrating or breaking a single
     * existing page: a row with no document renders exactly as it did before.
     */
    private String renderHtml(JsonNode template, JsonNode coupon, Map<String, String> tokens) {
        JsonNode document = parseObject(template.get("document"));
        String builderHtml = text(document, "html");
        if (sanitizer.hasRenderableHtml(builderHtml)) {
            return renderBuilderDocument(template, document, coupon, tokens);
        }
        return renderLegacyBlocks(template, coupon, tokens);
    }

    /** GrapesJS document → sanitized standalone HTML page. */
    private String renderBuilderDocument(JsonNode template, JsonNode document,
                                         JsonNode coupon, Map<String, String> tokens) {
        // Substitute BEFORE sanitizing so a token whose value contains markup is neutralized
        // by the sanitizer too, rather than being injected into already-cleaned HTML.
        String html = sanitizer.sanitizeHtml(fill(text(document, "html"), tokens));
        String css = sanitizer.sanitizeCss(text(document, "css"));

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>").append(esc(text(template, "name"))).append("</title>");
        // A mobile-first baseline under the builder's own CSS: images that never overflow
        // and sane box-sizing. The builder's rules win by cascade order.
        sb.append("<style>*,*::before,*::after{box-sizing:border-box}"
                + "body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#0f172a}"
                + "img{max-width:100%;height:auto}</style>");
        if (!css.isBlank()) {
            sb.append("<style>").append(css).append("</style>");
        }
        sb.append("</head><body>").append(html);

        String blurb = text(coupon, "personalBlurb");
        String pStatus = textOrDefault(coupon, "personalizationStatus", "none");
        if (blurb != null && !blurb.isBlank() && "approved".equalsIgnoreCase(pStatus)) {
            sb.append("<p class=\"blurb\" style=\"font-style:italic;color:#334155;padding:0 24px\">")
              .append(esc(blurb)).append("</p>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /** The original typed-block renderer. Unchanged behaviour for pre-builder pages. */
    private String renderLegacyBlocks(JsonNode template, JsonNode coupon, Map<String, String> tokens) {
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
                // An unfilled placeholder renders as NOTHING on the public page, not as an empty
                // frame. A generated draft ships image blocks the brand has not filled yet, and a
                // grey box reading "add an image" on a live page is worse than the section simply
                // not being there. The builder still shows the placeholder, so it stays findable.
                return url.isBlank() ? "" : "<img src=\"" + escAttr(url) + "\" alt=\""
                        + escAttr(textOrDefault(block, "alt", "")) + "\" loading=\"lazy\">";
            }
            case "video": {
                String url = textOrDefault(block, "url", "");
                if (url.isBlank()) {
                    return "";
                }
                // No autoplay: a page that starts playing on open is a page visitors close. `muted`
                // and `playsinline` are set so a brand that later adds autoplay in the builder gets
                // the behaviour mobile browsers actually permit rather than a silent no-op.
                String poster = textOrDefault(block, "poster", "");
                return "<video controls playsinline preload=\"metadata\" src=\"" + escAttr(url) + "\""
                        + (poster.isBlank() ? "" : " poster=\"" + escAttr(poster) + "\"")
                        + "></video>";
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

    // ---- stage + version history (Phase A.5) ---------------------------

    /**
     * The eight page stages. Held here as well as in the DB check constraint because a
     * 400 with a readable message is a better failure than a constraint violation
     * surfacing as a 500 three hops down.
     */
    private static final java.util.Set<String> STAGES = java.util.Set.of(
            "draft", "review", "approved", "creator_assigned",
            "content_needed", "ready_to_publish", "published", "performance_tracking");

    private void requireValidStage(String stage) {
        if (!STAGES.contains(stage)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown stage '" + stage + "'. Expected one of " + new java.util.TreeSet<>(STAGES));
        }
    }

    /**
     * Append a snapshot of the page as it was just saved.
     *
     * Best-effort by design: losing a history entry is a far smaller harm than failing a
     * save the user believes succeeded. A failure here is invisible to the caller, which
     * is the correct trade for an auxiliary record.
     */
    private void snapshotVersion(UUID brandId, JsonNode saved) {
        try {
            ObjectNode version = shape.objectMapper().createObjectNode();
            version.put("landingTemplateId", saved.get("id").asText());
            version.put("brandId", brandId.toString());
            version.put("name", textOrDefault(saved, "name", "Landing page"));
            version.put("stage", textOrDefault(saved, "stage", "draft"));
            // versionNo is deliberately omitted — the DAO stamps it under the unique
            // constraint, so two racing saves collide there rather than here.
            copyJsonbAsString(saved, version, "document", "blocks", "theme");
            dao.post("/landing-template-versions", version);
        } catch (RuntimeException ignored) {
            // history is auxiliary; never fail a save for it
        }
    }

    /** Version history for the campaign's page, newest first. */
    public JsonNode listVersions(UUID brandId, UUID campaignId) {
        JsonNode template = findTemplate(brandId, campaignId);
        if (template == null) {
            return shape.objectMapper().createArrayNode();
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("landingTemplateId", template.get("id").asText());
        JsonNode versions = dao.get("/landing-template-versions", q);
        return versions == null ? shape.objectMapper().createArrayNode() : versions;
    }

    /**
     * Restore a previous version by writing it forward as a new save.
     *
     * The restored content becomes the current page AND a new version at the head of the
     * history. Rewinding by deleting later versions would destroy the record of what was
     * undone, which is the one thing history exists to preserve.
     */
    public JsonNode restoreVersion(UUID brandId, UUID campaignId, int versionNo) {
        JsonNode template = findTemplate(brandId, campaignId);
        if (template == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No landing page for this campaign");
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("landingTemplateId", template.get("id").asText());
        JsonNode versions = dao.get("/landing-template-versions", q);
        JsonNode target = null;
        if (versions != null && versions.isArray()) {
            for (JsonNode v : versions) {
                if (v.hasNonNull("versionNo") && v.get("versionNo").asInt() == versionNo) {
                    target = v;
                    break;
                }
            }
        }
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Version " + versionNo + " not found");
        }

        ObjectNode restore = shape.objectMapper().createObjectNode();
        restore.put("campaignId", campaignId.toString());
        restore.put("name", textOrDefault(target, "name", "Landing page"));
        // Restoring content must not silently republish: a page rolled back to an older
        // draft should not inherit "published" from wherever it is now.
        restore.put("status", "draft");
        restore.put("stage", textOrDefault(target, "stage", "draft"));
        putParsed(restore, "document", target.get("document"));
        putParsed(restore, "blocks", target.get("blocks"));
        putParsed(restore, "theme", target.get("theme"));
        return saveTemplate(brandId, restore);
    }

    /**
     * Whether {@code saveTemplate} would update rather than create (M2.3).
     *
     * <p>Exists so the plan-limit check can distinguish the two. {@code saveTemplate} is an upsert
     * on {@code (brandId, campaignId)}, so a limit applied to every call would stop an
     * at-capacity account editing pages it already owns.
     *
     * <p>Errs toward "exists" on a null campaign: {@code saveTemplate} rejects that as a 400
     * anyway, and answering "does not exist" would spend a plan check on a request that is about
     * to fail validation.
     */
    public boolean existsForCampaign(UUID brandId, UUID campaignId) {
        return campaignId == null || findTemplate(brandId, campaignId) != null;
    }

    private JsonNode findTemplate(UUID brandId, UUID campaignId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        q.put("campaignId", campaignId.toString());
        JsonNode list = dao.get("/landing-templates", q);
        return list != null && list.isArray() && list.size() > 0 ? list.get(0) : null;
    }

    /**
     * Copy a jsonb field across as a STRING, matching how the DAO entity maps it.
     * The DAO may hand these back either already-parsed or as text depending on the hop,
     * so both shapes are normalized here rather than at each call site.
     */
    private void copyJsonbAsString(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            JsonNode node = from.get(field);
            if (node == null || node.isNull()) {
                continue;
            }
            try {
                to.put(field, node.isTextual() ? node.asText()
                        : shape.objectMapper().writeValueAsString(node));
            } catch (Exception ignored) {
                // skip this field; the snapshot is still worth writing without it
            }
        }
    }

    /** Set a field as real JSON, accepting either a parsed node or a JSON string. */
    private void putParsed(ObjectNode target, String field, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        try {
            target.set(field, value.isTextual()
                    ? shape.objectMapper().readTree(value.asText())
                    : value);
        } catch (Exception ignored) {
            // malformed stored JSON: leave the field unset rather than propagating junk
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

    /**
     * Normalize a jsonb field to an object node, accepting either a parsed object or a
     * JSON string. The DAO hands `document` back as text on some hops and as an object on
     * others (the entity maps it as String); both shapes have to work.
     */
    private JsonNode parseObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return shape.objectMapper().createObjectNode();
        }
        if (node.isObject()) {
            return node;
        }
        if (node.isTextual()) {
            try {
                JsonNode parsed = shape.objectMapper().readTree(node.asText());
                return parsed.isObject() ? parsed : shape.objectMapper().createObjectNode();
            } catch (Exception e) {
                return shape.objectMapper().createObjectNode();
            }
        }
        return shape.objectMapper().createObjectNode();
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
