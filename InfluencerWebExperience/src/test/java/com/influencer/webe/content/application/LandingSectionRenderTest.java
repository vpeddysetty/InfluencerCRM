package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The curated section renderer (roadmap PR-39 piece A).
 *
 * <p>Two properties are pinned here, and they are the two the plan rests on.
 *
 * <p><b>Precedence is {@code sections} &rarr; {@code document} &rarr; {@code blocks}.</b> The
 * migration is additive and rewrites no row, so the guarantee that matters is that a page with no
 * sections renders exactly as it did before. That is what makes the live published page safe on the
 * day this ships, and what makes the rollback in the plan's §5 a variable flip rather than a
 * restore.
 *
 * <p><b>Nothing brand-authored becomes markup.</b> The section path emits structure this code
 * chose and text it escaped — the "safe by construction" property {@code LandingDocumentSanitizer}
 * records the visual builder inverting. There is no sanitizer on this path, so if escaping regresses
 * there is nothing behind it.
 *
 * <p>Driven through {@code previewTemplate} rather than the private renderer: it is a real entry
 * point, and with no {@code couponId} it resolves the synthetic sample coupon without touching the
 * DAO — so these are pure render assertions with no transport stubbing beyond construction.
 */
class LandingSectionRenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LandingService service = new LandingService(
            new UnusedDao(), new ResponseShapeService(MAPPER), new LandingDocumentSanitizer(),
            null, null);

    /**
     * A DAO that fails loudly if called. These renders must not need one; a test that quietly
     * stubbed a response would hide a new round trip added to the public render path, which is
     * served to anonymous traffic.
     */
    private static class UnusedDao extends DaoGatewayClient {

        UnusedDao() {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
        }
    }

    // ---- helpers ---------------------------------------------------------

    private ObjectNode section(String type, String variant) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("type", type);
        if (variant != null) {
            s.put("variant", variant);
        }
        s.putObject("fields");
        return s;
    }

    /** Render a payload carrying the given sections. */
    private String renderSections(ObjectNode... sections) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("name", "Spring launch");
        ArrayNode list = payload.putArray("sections");
        for (ObjectNode s : sections) {
            list.add(s);
        }
        return service.previewTemplate(UUID.randomUUID(), payload);
    }

    /**
     * A coupon record as the DAO would return it.
     *
     * <p>{@code publicSlug} is the creator's stable per-page slug and {@code channel} is where the
     * link was posted — both real fields on the coupon projection, because the point of the tags
     * is that they are derived from records rather than typed by the brand.
     */
    private ObjectNode couponWith(String creatorSlug, String channel) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("id", UUID.randomUUID().toString());
        c.put("code", "MAYA20");
        c.put("discountType", "percent");
        c.put("discountValue", "20");
        c.put("publicSlug", creatorSlug);
        c.put("channel", channel);
        return c;
    }

    /** Render a hero with a CTA, personalized by a coupon the stub DAO returns. */
    private String renderWithCoupon(String landingUrl, ObjectNode coupon) {
        return renderWithCoupon(landingUrl, coupon, "Spring launch");
    }

    private String renderWithCoupon(String landingUrl, ObjectNode coupon, String pageName) {
        coupon.put("landingUrl", landingUrl);
        UUID brandId = UUID.randomUUID();
        coupon.put("brandId", brandId.toString());

        LandingService svc = new LandingService(new CouponDao(coupon),
                new ResponseShapeService(MAPPER), new LandingDocumentSanitizer(), null, null);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("name", pageName);
        payload.put("couponId", coupon.get("id").asText());
        ObjectNode hero = section("hero", "centred");
        ((ObjectNode) hero.get("fields")).put("headline", "New season");
        ((ObjectNode) hero.get("fields")).put("ctaLabel", "Shop now");
        payload.putArray("sections").add(hero);
        return svc.previewTemplate(brandId, payload);
    }

    /** Serves one coupon by id, which is the only DAO call a personalized preview makes. */
    private static class CouponDao extends DaoGatewayClient {

        private final JsonNode coupon;

        CouponDao(JsonNode coupon) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
            this.coupon = coupon;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            return path != null && path.startsWith("/influencer-campaign-codes/") ? coupon : null;
        }
    }

    // ---- precedence ------------------------------------------------------

    @Test
    @DisplayName("a page with sections renders from them, not from the builder document")
    void sectionsWinOverDocument() {
        ObjectNode hero = section("hero", "centred");
        ((ObjectNode) hero.get("fields")).put("headline", "New season");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("name", "Spring launch");
        payload.putArray("sections").add(hero);
        payload.putObject("document").put("html", "<p>the old builder page</p>").put("css", "");

        String html = service.previewTemplate(UUID.randomUUID(), payload);

        assertThat(html).contains("New season");
        assertThat(html).doesNotContain("the old builder page");
    }

    @Test
    @DisplayName("no sections falls through to the builder document, unchanged")
    void emptySectionsFallsBackToDocument() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("name", "Spring launch");
        payload.putArray("sections"); // present but empty — the state a cleared page is in
        payload.putObject("document").put("html", "<p>the old builder page</p>").put("css", "");

        String html = service.previewTemplate(UUID.randomUUID(), payload);

        assertThat(html).contains("the old builder page");
    }

    @Test
    @DisplayName("a page with neither still renders from legacy blocks")
    void fallsAllTheWayBackToBlocks() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("name", "Spring launch");
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "hero");
        block.put("text", "the legacy page");
        payload.putArray("blocks").add(block);

        String html = service.previewTemplate(UUID.randomUUID(), payload);

        assertThat(html).contains("the legacy page").contains("class=\"hero\"");
    }

    // ---- escaping --------------------------------------------------------

    @Test
    @DisplayName("brand-authored text is escaped, never emitted as markup")
    void escapesAuthoredText() {
        ObjectNode hero = section("hero", "centred");
        ((ObjectNode) hero.get("fields")).put("headline", "<script>alert(1)</script>");

        String html = renderSections(hero);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("a variant reaches the page only as a sanitized class name")
    void sanitizesVariantIntoClassName() {
        ObjectNode hero = section("hero", "centred\" onload=\"alert(1)");
        ((ObjectNode) hero.get("fields")).put("headline", "New season");

        String html = renderSections(hero);

        // The letters of "onload" survive INSIDE the class name — that is fine and is why this
        // asserts on the attribute rather than the substring. What must not exist is an onload
        // attribute, i.e. the quote that would close class= and the = that would open a new one.
        assertThat(html).doesNotContain("onload=");
        assertThat(html).doesNotContain("alert(1)");
        // Punctuation is dropped outright rather than escaped, so the class stays a class.
        assertThat(html).contains("class=\"s s-hero v-centred-onloadalert1\"");
    }

    @Test
    @DisplayName("a javascript: portrait URL is neutralized")
    void neutralizesDangerousUrls() {
        ObjectNode creator = section("creator", "portrait-left");
        ObjectNode f = (ObjectNode) creator.get("fields");
        f.put("quote", "I wear these every day");
        f.put("portrait", "javascript:alert(1)");

        String html = renderSections(creator);

        assertThat(html).doesNotContain("javascript:");
    }

    // ---- section behaviour -----------------------------------------------

    @Test
    @DisplayName("tokens are substituted inside section fields")
    void substitutesTokens() {
        ObjectNode offer = section("offer", "centred");
        ((ObjectNode) offer.get("fields")).put("headline", "Use {{coupon.code}} today");

        String html = renderSections(offer);

        // SAMPLE20 is the synthetic preview coupon resolved when no couponId is sent.
        assertThat(html).contains("Use SAMPLE20 today");
        assertThat(html).doesNotContain("{{coupon.code}}");
    }

    @Test
    @DisplayName("an empty field renders nothing rather than an empty tag")
    void omitsEmptyFields() {
        ObjectNode hero = section("hero", "centred");
        ((ObjectNode) hero.get("fields")).put("headline", "New season");

        String html = renderSections(hero);

        assertThat(html).contains("New season");
        assertThat(html).doesNotContain("class=\"eyebrow\"");
        assertThat(html).doesNotContain("class=\"subheadline\"");
    }

    @Test
    @DisplayName("a media section with no asset renders nothing at all")
    void omitsUnfilledMedia() {
        ObjectNode media = section("media", "contained");
        ((ObjectNode) media.get("fields")).put("caption", "a caption with no image");

        String html = renderSections(media);

        assertThat(html).doesNotContain("a caption with no image");
        assertThat(html).doesNotContain("<img");
    }

    @Test
    @DisplayName("the CTA points at the coupon URL, which no field can override")
    void ctaUsesCouponUrlOnly() {
        ObjectNode hero = section("hero", "centred");
        ObjectNode f = (ObjectNode) hero.get("fields");
        f.put("ctaLabel", "Shop now");
        f.put("href", "https://evil.example.com");

        String html = renderSections(hero);

        assertThat(html).contains("Shop now");
        assertThat(html).doesNotContain("evil.example.com");
    }

    @Test
    @DisplayName("an unknown section type degrades to text instead of failing the page")
    void unknownTypeDegrades() {
        ObjectNode odd = section("carousel3d", "spinning");
        ((ObjectNode) odd.get("fields")).put("body", "from a newer build");

        String html = renderSections(odd);

        assertThat(html).contains("from a newer build");
        assertThat(html).contains("<!doctype html>");
    }

    @Test
    @DisplayName("a creator handle gets exactly one @, however the brand typed it")
    void normalizesHandle() {
        ObjectNode withAt = section("creator", null);
        ObjectNode a = (ObjectNode) withAt.get("fields");
        a.put("quote", "Love it");
        a.put("handle", "@maya");

        ObjectNode without = section("creator", null);
        ObjectNode b = (ObjectNode) without.get("fields");
        b.put("quote", "Love it");
        b.put("handle", "maya");

        assertThat(renderSections(withAt)).contains(">@maya<").doesNotContain("@@maya");
        assertThat(renderSections(without)).contains(">@maya<");
    }

    @Test
    @DisplayName("sections render in the order the brand arranged them")
    void preservesOrder() {
        ObjectNode hero = section("hero", "centred");
        ((ObjectNode) hero.get("fields")).put("headline", "FIRST");
        ObjectNode legal = section("legal", null);
        ((ObjectNode) legal.get("fields")).put("body", "SECOND");

        String html = renderSections(hero, legal);

        assertThat(html.indexOf("FIRST")).isLessThan(html.indexOf("SECOND"));
    }

    // ---- campaign tracking (UTM) -----------------------------------------

    @Test
    @DisplayName("a CTA to a real destination carries campaign tags")
    void tagsOutboundCta() {
        String html = renderWithCoupon("https://shop.example.com/linen", couponWith("maya-okonjo", "instagram"));

        assertThat(html).contains("utm_source=instagram");
        assertThat(html).contains("utm_medium=referral");
        assertThat(html).contains("utm_campaign=spring-launch");
        assertThat(html).contains("utm_content=maya-okonjo");
    }

    @Test
    @DisplayName("an existing query string is kept and its own tags are not overwritten")
    void preservesExistingQuery() {
        String html = renderWithCoupon("https://shop.example.com/l?ref=abc&utm_source=newsletter",
                couponWith("maya-okonjo", "instagram"));

        assertThat(html).contains("ref=abc");
        // The brand's own utm_source wins; ours is not appended a second time.
        assertThat(html).contains("utm_source=newsletter");
        assertThat(html).doesNotContain("utm_source=instagram");
        // The tags they did NOT set are still added.
        assertThat(html).contains("utm_medium=referral");
    }

    @Test
    @DisplayName("a placeholder destination is left alone rather than tagged")
    void doesNotTagPlaceholderUrl() {
        String html = renderWithCoupon("#", couponWith("maya-okonjo", "instagram"));

        assertThat(html).doesNotContain("utm_");
    }

    @Test
    @DisplayName("a campaign name with punctuation becomes a clean tag, not an encoded one")
    void cleansTagValues() {
        String html = renderWithCoupon("https://shop.example.com/l",
                couponWith("maya", "instagram"), "Spring / Summer '26!");

        assertThat(html).contains("utm_campaign=spring-summer-26");
        // Assert on the href itself, not the whole document: the stylesheet legitimately
        // contains "!" (in !important), so a document-wide check would test the wrong thing.
        String href = html.substring(html.indexOf("href=\"https://shop.example.com"));
        href = href.substring(0, href.indexOf('"', 6));
        assertThat(href).doesNotContain("%2F").doesNotContain("!").doesNotContain("&#39;");
    }

    @Test
    @DisplayName("the page is mobile-first: a viewport meta and a min-width query, not max-width")
    void isMobileFirst() {
        ObjectNode hero = section("hero", "centred");
        ((ObjectNode) hero.get("fields")).put("headline", "New season");

        String html = renderSections(hero);

        assertThat(html).contains("width=device-width, initial-scale=1");
        // 640px is the point at which the CTA stops being a full-width bar. What is being
        // pinned is the DIRECTION — min-width, so the phone layout is the base and wider
        // screens opt in — not the specific number.
        assertThat(html).contains("@media(min-width:640px)");
        assertThat(html).doesNotContain("max-width:639");
    }
}
