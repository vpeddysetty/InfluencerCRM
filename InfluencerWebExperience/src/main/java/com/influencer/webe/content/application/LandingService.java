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
        // `sections` joins them for PR-39. stringifyJsonb omits an absent key rather than
        // writing null, which is what lets a builder-era save leave a section page's column
        // alone instead of blanking it.
        stringifyJsonb(payload, body, "blocks", "theme", "document", "sections");
        // A pending scheduled publish must survive an ordinary save. See LandingTemplateWrites for
        // why the DAO cannot guard this column itself, and for the two other writers that failed
        // this obligation until OP-18.
        LandingTemplateWrites.carryForwardScheduledPublish(existing, body);
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
        // Same for the section editor's unsaved list. Set only when present so a preview
        // request from the old builder does not push an empty `sections` in front of the
        // document it actually meant to preview.
        JsonNode sections = payload.get("sections");
        if (sections != null && !sections.isNull()) {
            template.set("sections", sections);
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
     * <p>Three paths, in strict precedence — <b>{@code sections} &rarr; {@code document} &rarr;
     * {@code blocks}</b>:
     * <ul>
     *   <li><b>Section path</b> (PR-39) — typed, curated sections rendered server-side. Values are
     *       escaped as text, never emitted as markup, so this path is safe by construction in the
     *       way {@code LandingDocumentSanitizer}'s own header says the typed-block renderer was and
     *       the visual builder is not.</li>
     *   <li><b>Builder path</b> — sanitized GrapesJS HTML/CSS. Tokens are substituted into
     *       the HTML with escaped values, so a coupon code containing markup cannot inject.</li>
     *   <li><b>Legacy path</b> — the original typed-block renderer, unchanged.</li>
     * </ul>
     *
     * <p><b>Why sections win over an existing document.</b> A page only has both if someone opened
     * a builder page in the section editor and saved. That save is the user choosing the new
     * editor; continuing to serve the stale GrapesJS document would show them a public page that
     * ignores the edit they just made. The document is deliberately left in the row rather than
     * cleared, so flipping {@code web-experience.landing.editor} back to {@code builder} restores
     * the old page intact — the rollback the plan's §5 promises is a variable flip.
     *
     * <p>The fallback chain is what lets each phase ship without migrating or breaking a single
     * existing page: a row with no sections renders exactly as it did before.
     */
    private String renderHtml(JsonNode template, JsonNode coupon, Map<String, String> tokens) {
        JsonNode sections = parseArray(template.get("sections"));
        if (!sections.isEmpty()) {
            return renderSections(template, sections, coupon, tokens);
        }
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

    /**
     * Curated typed sections &rarr; a standalone HTML page (PR-39 piece A).
     *
     * <p><b>The security property this restores.</b> Nothing authored by the brand reaches the
     * output as markup. Every value goes through {@link #esc} or {@link #escAttr}, and the page
     * structure comes from this method rather than from stored HTML — so there is no sanitizer in
     * this path because there is nothing to sanitize. That is the "safe by construction" property
     * {@code LandingDocumentSanitizer}'s header records the visual builder inverting.
     *
     * <p><b>Layout is not authorable.</b> A section carries a {@code type}, a {@code variant} and
     * named {@code fields}. No field is a colour, font, size or position: those live in the
     * stylesheet below, keyed by type and variant. This is the whole reason the editor "cannot look
     * wrong" — a brand manager picks a designed variant instead of nudging a box.
     *
     * <p><b>Unknown types and variants degrade, never throw.</b> A page is rendered for anonymous
     * public traffic; a section type from a newer build than the one serving the request must not
     * 500 someone's live page. An unrecognized type renders as plain text and an unrecognized
     * variant falls back to the type's default styling.
     */
    private String renderSections(JsonNode template, JsonNode sections,
                                  JsonNode coupon, Map<String, String> tokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>").append(esc(text(template, "name"))).append("</title>");
        sb.append(SECTION_FONTS);
        sb.append("<style>").append(SECTION_CSS).append("</style>");
        sb.append("</head><body>");

        // The page name doubles as the utm_campaign value, so it is resolved once here rather
        // than re-read per section.
        String pageName = textOrDefault(template, "name", "");
        for (JsonNode section : sections) {
            sb.append(renderSection(section, coupon, tokens, pageName));
        }

        // Creator personalization blurb, on the same terms as the other two renderers: shown only
        // once approved. Kept outside the section list because it is not something the brand
        // authored or can position — it is the creator's own words, appended by the platform.
        String blurb = text(coupon, "personalBlurb");
        String pStatus = textOrDefault(coupon, "personalizationStatus", "none");
        if (blurb != null && !blurb.isBlank() && "approved".equalsIgnoreCase(pStatus)) {
            sb.append("<section class=\"s s-blurb\"><p class=\"blurb\">")
              .append(esc(blurb)).append("</p></section>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * One typed section.
     *
     * <p>Piece A renders the types the plan's schema already names. Piece B is the design work that
     * gives each one its full variant set; the field names read here are the ones the plan fixes,
     * so B extends these branches rather than replacing them.
     */
    private String renderSection(JsonNode section, JsonNode coupon, Map<String, String> tokens,
                                 String pageName) {
        String type = textOrDefault(section, "type", "text");
        JsonNode f = parseObject(section.get("fields"));
        String variant = textOrDefault(section, "variant", "");
        // The variant reaches the page ONLY as a sanitized class name. It is brand-supplied data
        // like any other field, and interpolating it into an attribute unchecked would be an
        // injection point dressed up as a layout choice.
        String cls = "s s-" + slugClass(type) + (variant.isBlank() ? "" : " v-" + slugClass(variant));

        switch (type) {
            case "hero":
                return "<section class=\"" + escAttr(cls) + "\">"
                        + optional("p", "eyebrow", f, tokens)
                        + optional("h1", "headline", f, tokens)
                        + optional("p", "subheadline", f, tokens)
                        + ctaLink(f, coupon, tokens, pageName)
                        + "</section>";
            case "media": {
                String url = textOrDefault(f, "asset", "");
                if (url.isBlank()) {
                    // An unfilled placeholder renders as NOTHING, matching renderBlock's image
                    // case and for the same reason: an empty frame on a live public page is worse
                    // than the section simply not being there. The editor still shows it.
                    return "";
                }
                return "<section class=\"" + escAttr(cls) + "\"><figure>"
                        + "<img src=\"" + escAttr(url) + "\" alt=\""
                        + escAttr(fill(textOrDefault(f, "altText", ""), tokens)) + "\" loading=\"lazy\">"
                        + optional("figcaption", "caption", f, tokens)
                        + "</figure></section>";
            }
            case "offer":
                // The .band wrapper is what centres the contents inside a full-width tinted
                // section. Without it the auto margins land on headings and paragraphs, whose
                // own margin shorthand resets them, and the text pins to the left edge.
                return "<section class=\"" + escAttr(cls) + "\"><div class=\"band\">"
                        + optional("h2", "headline", f, tokens)
                        + optional("p", "supporting", f, tokens)
                        // The coupon is rendered by the platform, not authored: the brand writes
                        // the words around the code, never the code itself.
                        + couponLine(coupon)
                        + ctaLink(f, coupon, tokens, pageName)
                        + "</div></section>";
            case "proof": {
                StringBuilder items = new StringBuilder();
                for (JsonNode item : parseArray(f.get("items"))) {
                    items.append("<li class=\"proof-item\">")
                         .append(optional("h3", "title", item, tokens))
                         .append(optional("p", "body", item, tokens))
                         .append("</li>");
                }
                if (items.length() == 0) {
                    return "";
                }
                return "<section class=\"" + escAttr(cls) + "\">"
                        + optional("h2", "headline", f, tokens)
                        + "<ul class=\"proof\">" + items + "</ul></section>";
            }
            case "creator": {
                String quote = fill(textOrDefault(f, "quote", ""), tokens);
                if (quote.isBlank()) {
                    return "";
                }
                String portrait = textOrDefault(f, "portrait", "");
                return "<section class=\"" + escAttr(cls) + "\"><div class=\"band\">"
                        + "<figure class=\"creator\">"
                        + (portrait.isBlank() ? "" : "<img class=\"portrait\" src=\"" + escAttr(portrait)
                            + "\" alt=\"\" loading=\"lazy\">")
                        + "<blockquote>" + esc(quote) + "</blockquote>"
                        + "<figcaption>" + esc(fill(textOrDefault(f, "name", ""), tokens))
                        + handleSuffix(f) + "</figcaption>"
                        + "</figure></div></section>";
            }
            case "signup":
                // Renders the CTA, not a form. A form here would collect personal data on an
                // anonymous public page with nowhere to POST it; the signup section's job in this
                // piece is to send the visitor to the shop carrying their code.
                return "<section class=\"" + escAttr(cls) + "\">"
                        + optional("h2", "headline", f, tokens)
                        + ctaLink(f, coupon, tokens, pageName)
                        + "</section>";
            case "legal":
                return "<section class=\"" + escAttr(cls) + "\">"
                        + optional("p", "body", f, tokens) + "</section>";
            case "text":
            default:
                return "<section class=\"" + escAttr(cls) + "\">"
                        + optional("h2", "headline", f, tokens)
                        + optional("p", "body", f, tokens) + "</section>";
        }
    }

    /** A field rendered in a tag, or nothing at all when the brand left it empty. */
    private String optional(String tag, String field, JsonNode fields, Map<String, String> tokens) {
        String v = fill(textOrDefault(fields, field, ""), tokens);
        if (v.isBlank()) {
            return "";
        }
        return "<" + tag + " class=\"" + field + "\">" + esc(v) + "</" + tag + ">";
    }

    /**
     * The call to action.
     *
     * <p>The href is the coupon's landing URL — never a brand-authored field. A section editor that
     * let the brand type a destination would be a way to publish an arbitrary outbound link from a
     * page carrying the platform's tracking, and the CTA's whole job is to land on the tracked URL.
     */
    private String ctaLink(JsonNode fields, JsonNode coupon, Map<String, String> tokens, String pageName) {
        String label = fill(textOrDefault(fields, "ctaLabel", ""), tokens);
        if (label.isBlank()) {
            return "";
        }
        String href = withCampaignTags(textOrDefault(coupon, "landingUrl", "#"), coupon, pageName);
        return "<a class=\"cta\" href=\"" + escAttr(href) + "\">" + esc(label) + "</a>";
    }

    /**
     * Append UTM tags to an outbound CTA, so a page with no coupon is still attributable.
     *
     * <p><b>Why this exists.</b> A coupon attributes a SALE — the code is entered at checkout, so
     * the purchase points back at the creator. A page published without one has no such link, and
     * before this the brand simply got nothing. UTM tags attribute the CLICK instead: which
     * campaign and which creator sent the visit. That is strictly less than a coupon and is meant
     * to be — it answers "who sends traffic", not "who drives revenue", and the publish warning
     * says so rather than implying the two are equivalent.
     *
     * <p><b>Why not fingerprinting.</b> The obvious-looking alternative — identifying returning
     * visitors by IP, device or browser characteristics — was rejected outright. It is processing
     * personal data on an anonymous public page with no consent gate, which is exactly what
     * {@code consent_records} exists to prevent, and it is guesswork that silently mis-attributes
     * revenue. Being able to say "this page has no sales attribution" is more useful than a number
     * that is quietly wrong.
     *
     * <p><b>Derived, never authored.</b> Every value comes from the campaign and coupon records.
     * A brand-typed tag would let the page write arbitrary query parameters onto its own outbound
     * link, and the point of the tags is that they mean the same thing on every page.
     *
     * <p>An existing query string is preserved and a tag already present is not overwritten: the
     * brand's own {@code landingUrl} may already carry tracking that it would be rude to clobber.
     */
    private String withCampaignTags(String url, JsonNode coupon, String pageName) {
        if (url == null || url.isBlank() || "#".equals(url)) {
            return url == null || url.isBlank() ? "#" : url;
        }
        // Only http(s) destinations are tagged. escAttr would neutralize anything else anyway,
        // but appending a query string to a non-URL is meaningless rather than merely unsafe.
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return url;
        }

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("utm_source", tagValue(textOrDefault(coupon, "channel", "influencrm")));
        tags.put("utm_medium", "referral");
        // The page name, not the campaign UUID. `campaignName` is not on the coupon projection,
        // and a raw id in an analytics report is unreadable — "spring-launch" tells the brand
        // which campaign it is looking at, which is the entire purpose of the tag.
        String campaign = pageName == null ? "" : pageName;
        if (!campaign.isBlank()) {
            tags.put("utm_campaign", tagValue(campaign));
        }
        // The creator's public slug, which is already the stable per-creator identifier the
        // coupon URL uses — so the tag and the /s/{slug}/{creator} path name the same person.
        String creator = textOrDefault(coupon, "publicSlug", "");
        if (!creator.isBlank()) {
            tags.put("utm_content", tagValue(creator));
        }

        String existing = url.contains("?") ? url.substring(url.indexOf('?') + 1).toLowerCase(Locale.ROOT) : "";
        StringBuilder sb = new StringBuilder(url);
        char sep = url.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getValue().isBlank() || existing.contains(e.getKey() + "=")) {
                continue; // already tagged by the brand: leave their value alone
            }
            sb.append(sep).append(e.getKey()).append('=').append(e.getValue());
            sep = '&';
        }
        return sb.toString();
    }

    /**
     * Reduce a value to something safe inside a query string.
     *
     * <p>Whitelisted to unreserved URL characters rather than percent-encoded, for the same reason
     * {@code slugClass} whitelists: a tag is an identifier, not free text. Anything else becomes a
     * hyphen, so a campaign named "Spring / Summer" tags as {@code spring-summer} instead of
     * carrying an encoded slash that every analytics tool renders differently.
     */
    private String tagValue(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean lastHyphen = false;
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '_')) {
                out.append(c);
                lastHyphen = false;
            } else if (!lastHyphen && out.length() > 0) {
                out.append('-');
                lastHyphen = true;
            }
        }
        String v = out.toString();
        while (v.endsWith("-")) {
            v = v.substring(0, v.length() - 1);
        }
        // Long tags get truncated by some analytics tools mid-word, which turns two campaigns
        // into one row. Cutting at a boundary we choose keeps them distinct.
        return v.length() > 64 ? v.substring(0, 64) : v;
    }

    /** The coupon code line, rendered identically to the legacy {@code couponBlock}. */
    private String couponLine(JsonNode coupon) {
        String code = textOrDefault(coupon, "code", "");
        if (code.isBlank()) {
            return "";
        }
        return "<p class=\"coupon\">Use code <span class=\"code\">" + esc(code) + "</span>"
                + " for " + esc(describeDiscount(coupon)) + "</p>";
    }

    /** " @handle" when the creator section carries one, with the @ added exactly once. */
    private String handleSuffix(JsonNode fields) {
        String handle = textOrDefault(fields, "handle", "");
        if (handle.isBlank()) {
            return "";
        }
        String normalized = handle.startsWith("@") ? handle : "@" + handle;
        return " <span class=\"handle\">" + esc(normalized) + "</span>";
    }

    /**
     * Reduce a type or variant name to a safe CSS class fragment.
     *
     * <p>Whitelist, not blacklist: anything that is not an ASCII letter, digit or separator is
     * dropped outright. {@code escAttr} would already stop markup escaping the attribute, but a
     * class name is not free text, and narrowing it here means a malformed variant produces an
     * unstyled section rather than anything creative.
     */
    private String slugClass(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c < 128 && Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (c == '-' || c == ' ' || c == '_') {
                out.append('-');
            }
        }
        return out.toString();
    }

    /**
     * The section stylesheet.
     *
     * <p><b>This is the design, and it is deliberately not authorable.</b> Every colour, size and
     * spacing decision lives here rather than in a section's fields — that is the mechanism by
     * which a page cannot be made to look wrong. Piece B expands this per type and variant.
     *
     * <p>Mobile-first, so previewing is confirmation rather than a second design job: the base
     * rules are the phone layout and the single media query widens them. Most creator traffic
     * lands on a phone, and the builder's failure mode was tuning desktop and never re-checking.
     */
    /**
     * Font loading for the public page.
     *
     * <p>Newsreader for headings, Inter for body — the approved editorial direction. Loaded from
     * Google Fonts with {@code display=swap} so text is readable in a fallback face immediately
     * rather than invisible while the webfont downloads: this page is often opened on a phone on
     * mobile data, from a link in a creator's bio, and a blank hero is a closed tab.
     *
     * <p>The stack below every custom face is a real fallback, not decoration. If the request is
     * blocked — corporate network, privacy blocker, offline — the page renders in Georgia and
     * system sans and still looks deliberate.
     */
    private static final String SECTION_FONTS =
            "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
            + "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
            + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?"
            + "family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,500;0,6..72,600;1,6..72,400"
            + "&family=Inter:wght@400;500;600&display=swap\">";

    /**
     * The section stylesheet.
     *
     * <p><b>This is the design, and it is deliberately not authorable.</b> Every colour, size and
     * spacing decision lives here rather than in a section's fields — that is the mechanism by
     * which a page cannot be made to look wrong. A brand picks a designed variant; it cannot nudge
     * a box three pixels off centre and publish it.
     *
     * <p><b>The direction is Swiss/minimal structure with editorial typography</b>: a strict
     * measure, generous vertical rhythm, one accent colour and no decorative borders. Newsreader
     * carries the headlines because the page is a piece of writing by a creator about a product,
     * and Inter carries everything functional. The accent is terracotta rather than the app's
     * indigo — this page is the brand's shopfront, not our product UI, and it should not look like
     * a SaaS dashboard.
     *
     * <p><b>Why the palette is warm and near-monochrome.</b> A landing page competes with the
     * product photography on it. A loud interface palette fights the image; a warm off-white
     * ground with near-black text lets the photograph and the offer be the only saturated things
     * on screen.
     *
     * <p>Mobile-first, so previewing is confirmation rather than a second design job: the base
     * rules are the phone layout and the media queries widen them. Most creator traffic lands on
     * a phone, and the builder's failure mode was tuning desktop and never re-checking.
     *
     * <p><b>Contrast is checked, not assumed.</b> Every foreground/background pair here was
     * computed against WCAG AA 4.5:1 on <i>each</i> of the three grounds a section can sit on
     * (--paper, --paper-alt, --accent-soft) rather than only the default one. Two values moved as
     * a result: --ink-mute was #8A8179 (3.60:1 on paper — a fail at the small sizes it is actually
     * used at, the eyebrow and the legal line) and the accent was #B8543A (4.05:1 on the offer
     * background). Both now pass everywhere they appear; worst case in the sheet is 4.79:1.
     * Reduced motion is honoured: the one transition is removed rather than shortened.
     */
    private static final String SECTION_CSS =
            // ---- tokens ----
            ":root{--ink:#2A2724;--ink-soft:#5C554E;--ink-mute:#6E655E;"
            + "--paper:#FAF8F5;--paper-alt:#F2EDE7;--rule:#E2DAD1;"
            + "--accent:#A84A32;--accent-ink:#FFFFFF;--accent-soft:#F6E9E4;"
            + "--serif:'Newsreader',Georgia,'Times New Roman',serif;"
            + "--sans:'Inter',system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;"
            + "--measure:34rem;--wide:60rem}"
            + "*,*::before,*::after{box-sizing:border-box}"
            + "body{margin:0;background:var(--paper);color:var(--ink);font-family:var(--sans);"
            + "font-size:1rem;line-height:1.6;-webkit-font-smoothing:antialiased;"
            + "text-rendering:optimizeLegibility}"
            + "img{max-width:100%;height:auto;display:block}"
            // ---- section frame ----
            + ".s{max-width:var(--measure);margin:0 auto;padding:40px 24px}"
            + ".s-hero{padding-top:64px;padding-bottom:8px}"
            // ---- type ----
            + ".eyebrow{font-family:var(--sans);text-transform:uppercase;letter-spacing:.14em;"
            + "font-size:.6875rem;font-weight:600;color:var(--ink-mute);margin:0 0 1rem}"
            + "h1.headline,h2.headline{font-family:var(--serif);font-weight:500;"
            + "letter-spacing:-.015em;color:var(--ink);margin:0 0 1rem;text-wrap:balance}"
            + "h1.headline{font-size:2.25rem;line-height:1.1}"
            + "h2.headline{font-size:1.625rem;line-height:1.2}"
            + ".subheadline{font-size:1.0625rem;line-height:1.6;color:var(--ink-soft);"
            + "margin:0 0 1.5rem;text-wrap:pretty}"
            + ".s p{margin:0 0 1rem}"
            + ".s p:last-child{margin-bottom:0}"
            // ---- call to action ----
            // 48px min height and a full-width tap target on phones: the CTA is the one thing on
            // the page that must never be a near-miss with a thumb.
            + ".cta{display:block;width:100%;text-align:center;margin-top:1.75rem;"
            + "background:var(--accent);color:var(--accent-ink);text-decoration:none;"
            + "padding:.9rem 1.5rem;min-height:48px;border-radius:2px;font-family:var(--sans);"
            + "font-weight:600;font-size:1rem;letter-spacing:.01em;"
            + "transition:background-color .18s ease}"
            + ".cta:hover{background:#8E3C28}"
            // Never remove the focus ring — replace it with a better one. Keyboard users on a
            // public page are often screen-reader users, and this is the only interactive element.
            + ".cta:focus-visible{outline:2px solid var(--ink);outline-offset:3px}"
            // ---- offer / coupon ----
            // A tinted band spans the viewport while its CONTENTS stay on the measure. The inner
            // wrapper does the centring, because per-child auto margins are silently undone by
            // the margin shorthand on headings and paragraphs.
            + ".s-offer,.s-creator{max-width:none;padding-left:0;padding-right:0}"
            + ".s-offer{background:var(--accent-soft)}"
            + ".s-offer>.band,.s-creator>.band{max-width:var(--measure);margin:0 auto;"
            + "padding:0 24px}"
            // text-wrap:pretty keeps "for 20% off" from being orphaned onto its own line beside
            // the code chip, which is where it broke in the narrow right column of the split
            // variant. The chip itself must never break mid-code.
            + ".coupon{font-family:var(--sans);font-size:.9375rem;color:var(--ink-soft);"
            + "text-wrap:pretty}"
            + ".code{display:inline-block;font-family:ui-monospace,'SFMono-Regular',Menlo,monospace;"
            + "font-weight:600;letter-spacing:.12em;background:var(--paper);color:var(--accent);"
            + "border:1px solid var(--accent);padding:.4rem .75rem;border-radius:2px;"
            + "font-size:1.0625rem;margin:0 .15rem;white-space:nowrap}"
            // ---- proof ----
            + ".proof{list-style:none;margin:1.5rem 0 0;padding:0;display:grid;gap:1.75rem}"
            + ".proof-item{border-top:1px solid var(--rule);padding-top:1rem}"
            + ".proof-item h3{font-family:var(--sans);font-size:.9375rem;font-weight:600;"
            + "margin:0 0 .35rem;color:var(--ink)}"
            + ".proof-item p{margin:0;color:var(--ink-soft);font-size:.9375rem}"
            // ---- creator ----
            + ".s-creator{background:var(--paper-alt)}"
            + ".creator{margin:0}"
            + ".portrait{width:64px;height:64px;border-radius:50%;object-fit:cover;"
            + "margin-bottom:1.25rem}"
            + ".creator blockquote{margin:0;font-family:var(--serif);font-size:1.375rem;"
            + "line-height:1.4;font-weight:400;color:var(--ink);text-wrap:pretty}"
            + ".creator figcaption{margin-top:1rem;font-family:var(--sans);font-size:.875rem;"
            + "color:var(--ink-mute)}"
            + ".creator .handle{color:var(--accent)}"
            // ---- media ----
            + ".s-media figure{margin:0}"
            + ".s-media img{border-radius:2px}"
            + ".s-media figcaption{margin-top:.75rem;font-size:.8125rem;color:var(--ink-mute);"
            + "font-style:italic;font-family:var(--serif)}"
            // ---- legal / blurb ----
            // The legal line is a footnote to the section above it, not a section in its own
            // right; a full 40px of air above it reads as an unfinished page.
            + ".s-legal{padding-top:4px}"
            + ".s-legal p{color:var(--ink-mute);font-size:.75rem;line-height:1.5}"
            + ".s-blurb{padding-top:8px}"
            + ".blurb{font-family:var(--serif);font-style:italic;color:var(--ink-soft);"
            + "font-size:1.0625rem}"
            // ---- variants ----
            // Two-column text collapses to one on a phone. The column-rule is the only vertical
            // line in the design and exists to keep the two columns from reading as one wrapped
            // block; it appears with the columns, never before.
            + ".v-two-column .body{margin-top:.5rem}"
            + ".s-media.v-full-bleed{max-width:none;padding-left:0;padding-right:0}"
            + ".s-media.v-full-bleed img{border-radius:0;width:100%}"
            + ".s-media.v-full-bleed figcaption{max-width:var(--measure);margin-left:auto;"
            + "margin-right:auto;padding:0 24px}"
            // ---- tablet and up ----
            + "@media(min-width:640px){"
            + ".s{padding:56px 32px}.s-hero{padding-top:96px}"
            + "h1.headline{font-size:3rem}h2.headline{font-size:2rem}"
            + ".subheadline{font-size:1.125rem}"
            // The CTA stops being full-width once there is room for it to read as a button
            // rather than a bar.
            + ".cta{display:inline-block;width:auto}"
            + ".v-centred{text-align:center}"
            + ".v-centred .cta{min-width:16rem}"
            // `left` is the base layout, so it needs no rules of its own — but it is named in the
            // schema, and a variant that silently resolves to the default is indistinguishable
            // from a broken one. This rule exists to make the choice real and to say so.
            + ".v-left{text-align:left}"
            // Split puts the words beside the action instead of above it. Two columns with the
            // CTA bottom-aligned to the text block, which is what makes it read as a pairing
            // rather than as a hero with an orphaned button.
            // Split puts the words in the left column and the action in the right. The right
            // column is a nested flow rather than more grid cells: an earlier version placed the
            // coupon and the CTA on explicit grid rows and they overlapped, because two children
            // assigned overlapping row spans stack on top of each other rather than sequencing.
            // Letting the right column lay itself out is both simpler and impossible to collide.
            + ".v-split{max-width:var(--wide)}"
            + ".v-split>.band,.s-hero.v-split{display:grid;"
            + "grid-template-columns:minmax(0,1.35fr) minmax(0,1fr);"
            + "column-gap:3rem;align-items:start}"
            + ".v-split .eyebrow,.v-split .headline,.v-split .subheadline,"
            + ".v-split .supporting{grid-column:1}"
            + ".v-split .subheadline,.v-split .supporting{margin-bottom:0}"
            // Everything actionable goes to column 2, stacked in source order and bottom-aligned
            // so the button sits on the same baseline as the last line of text.
            + ".v-split .coupon,.v-split .cta{grid-column:2;margin-top:0}"
            + ".v-split .coupon{grid-row:1;align-self:start;margin-bottom:1rem}"
            + ".v-split .cta{grid-row:2;align-self:start;justify-self:start}"
            // With no coupon (a hero) the CTA is the only right-column child and would sit on
            // row 2 with an empty row above it; pull it up.
            + ".s-hero.v-split .cta{grid-row:1}"
            // Quote-first drops the portrait below the quote, so the words land before the face.
            + ".v-quote-first .creator{display:flex;flex-direction:column}"
            + ".v-quote-first blockquote{order:1}"
            + ".v-quote-first .portrait{order:2;margin:1.25rem 0 .5rem}"
            + ".v-quote-first figcaption{order:3;margin-top:0}"
            // Inline lays the closing headline beside its button instead of above it.
            + ".s-signup.v-inline{max-width:var(--wide)}"
            + ".s-signup.v-inline{display:grid;grid-template-columns:1fr auto;"
            + "column-gap:2rem;align-items:center}"
            + ".s-signup.v-inline .headline{margin-bottom:0}"
            + ".s-signup.v-inline .cta{margin-top:0}"
            // Two-column text uses CSS columns rather than a grid: the point is that ONE body of
            // prose flows across two columns, which a grid cannot express without splitting the
            // text into two fields the brand would have to balance by hand.
            + ".v-two-column{max-width:var(--wide)}"
            + ".v-two-column .body{column-count:2;column-gap:3rem;column-rule:1px solid var(--rule)}"
            // Contained media is the base; named for the same reason as v-left.
            + ".s-media.v-contained img{max-width:100%}"
            // A grid of reasons is the default; the id is `grid` in the schema, so both the
            // explicit class and the bare .s-proof must produce it.
            + ".v-grid .proof{grid-template-columns:repeat(3,1fr)}"
            // The 3-up grid needs more room than a reading measure allows — at 34rem the three
            // columns break to 3-4 words per line, which reads as broken rather than as a grid.
            // The whole section widens, so the heading stays aligned with the columns under it.
            + ".s-proof{max-width:var(--wide)}"
            + ".proof{grid-template-columns:repeat(3,1fr);gap:2.5rem}"
            + ".proof-item p{font-size:.875rem}"
            // A stacked list is prose again, so it goes back to the reading measure.
            + ".s-proof.v-stacked-list{max-width:var(--measure)}"
            + ".v-stacked-list .proof{grid-template-columns:1fr}"
            // Only the portrait and the quote share the two columns. The caption spans both:
            // left in the 64px track it wrapped "Maya Okonjo" onto three lines.
            + ".v-portrait-left .creator{display:grid;grid-template-columns:64px 1fr;"
            + "column-gap:1.5rem;row-gap:1rem;align-items:start}"
            + ".v-portrait-left .portrait{margin-bottom:0}"
            + ".v-portrait-left blockquote{grid-column:2}"
            + ".v-portrait-left figcaption{grid-column:1/-1;margin-top:0}"
            + ".creator blockquote{font-size:1.5rem}"
            + ".s-offer>.band,.s-creator>.band,.s-media.v-full-bleed figcaption{"
            + "padding-left:32px;padding-right:32px}"
            + "}"
            // ---- desktop ----
            + "@media(min-width:1024px){"
            + ".s{padding:72px 32px}.s-hero{padding-top:120px}"
            + "h1.headline{font-size:3.5rem}"
            + "}"
            // ---- honoured, not ignored ----
            + "@media(prefers-reduced-motion:reduce){"
            + "*{animation:none!important;transition:none!important}"
            + "}";

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
            copyJsonbAsString(saved, version, "document", "blocks", "theme", "sections");
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
        putParsed(restore, "sections", target.get("sections"));
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
