package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The share kit (roadmap PR-45).
 *
 * <p>The assertions that earn their place are the ones about obligations and dead ends: that the
 * disclosure is always present and separable, that an unpublished page is refused rather than
 * handed over as a link nobody can open, and that the caption leads with the CODE on Instagram
 * because a URL there is inert text.
 */
class ShareKitServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PAGE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COUPON = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static class StubDao extends DaoGatewayClient {
        private final Map<String, JsonNode> responses = new LinkedHashMap<>();

        StubDao() {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    return null;
                }
            }, null);
        }

        StubDao with(String path, JsonNode node) {
            responses.put(path, node);
            return this;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            return responses.get(path);
        }
    }

    private ObjectNode page(String status, UUID owner) {
        ObjectNode t = MAPPER.createObjectNode();
        t.put("id", PAGE.toString());
        t.put("brandId", owner.toString());
        t.put("status", status);
        t.put("publicSlug", "c-spring");
        var sections = t.putArray("sections");
        ObjectNode hero = sections.addObject();
        hero.put("type", "hero");
        hero.putObject("fields").put("headline", "Trail shoes for the wet months")
                .put("subheadline", "Woven in Portugal.");
        ObjectNode offer = sections.addObject();
        offer.put("type", "offer");
        offer.putObject("fields").put("headline", "20% off your first order");
        return t;
    }

    private ObjectNode coupon(UUID owner, String slug) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("id", COUPON.toString());
        c.put("brandId", owner.toString());
        c.put("code", "MAYA20");
        if (slug != null) {
            c.put("publicSlug", slug);
        }
        return c;
    }

    private ShareKitService service(ObjectNode template, ObjectNode coupon) {
        StubDao dao = new StubDao()
                .with("/landing-templates/" + PAGE, template)
                .with("/influencer-campaign-codes/" + COUPON, coupon);
        return new ShareKitService(dao, new ResponseShapeService(MAPPER), "https://tejdux.com");
    }

    private String captionFor(JsonNode kit, String platform) {
        for (JsonNode c : kit.get("captions")) {
            if (platform.equals(c.get("platform").asText())) {
                return c.get("body").asText();
            }
        }
        return null;
    }

    @Test
    @DisplayName("an unpublished page is refused rather than shared as a dead link")
    void refusesUnpublished() {
        assertThatThrownBy(() -> service(page("draft", BRAND), coupon(BRAND, "maya")).forCoupon(BRAND, PAGE, COUPON))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Publish the page");
    }

    @Test
    @DisplayName("another brand's page is not found, and the message does not confirm it exists")
    void refusesAnotherTenant() {
        assertThatThrownBy(() -> service(page("published", OTHER), coupon(OTHER, "maya")).forCoupon(BRAND, PAGE, COUPON))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("the disclosure is always present and is its own field, not buried in the caption")
    void disclosureIsSeparable() {
        // Its own field so a UI cannot render an editable caption box that quietly drops it. This
        // is an FTC obligation, not a preference.
        JsonNode kit = service(page("published", BRAND), coupon(BRAND, "maya")).forCoupon(BRAND, PAGE, COUPON);

        assertThat(kit.get("disclosure").asText()).isEqualTo("#ad");
    }

    @Test
    @DisplayName("a page with its own legal wording wins over the #ad default")
    void brandDisclosureWins() {
        // A `legal` section is what the brand's counsel approved; overriding it with a generic tag
        // would substitute our judgement for theirs on a legal obligation.
        ObjectNode template = page("published", BRAND);
        ObjectNode legal = ((com.fasterxml.jackson.databind.node.ArrayNode) template.get("sections")).addObject();
        legal.put("type", "legal");
        legal.putObject("fields").put("body", "Paid partnership with Trailhead. #ad");

        JsonNode kit = service(template, coupon(BRAND, "maya")).forCoupon(BRAND, PAGE, COUPON);

        assertThat(kit.get("disclosure").asText()).isEqualTo("Paid partnership with Trailhead. #ad");
    }

    @Test
    @DisplayName("the Instagram caption leads with the CODE, because a link there is inert text")
    void instagramLeadsWithTheCode() {
        JsonNode kit = service(page("published", BRAND), coupon(BRAND, "maya")).forCoupon(BRAND, PAGE, COUPON);
        String caption = captionFor(kit, "instagram");

        assertThat(caption).contains("MAYA20").contains("Link in bio");
        // The URL is deliberately absent: unclickable on that platform, it is noise in a caption
        // with a hard length limit.
        assertThat(caption).doesNotContain("https://");
    }

    @Test
    @DisplayName("the link is per-creator when the coupon has a slug — that is what attributes the visit")
    void perCreatorLink() {
        JsonNode kit = service(page("published", BRAND), coupon(BRAND, "maya")).forCoupon(BRAND, PAGE, COUPON);

        assertThat(kit.get("link").asText()).isEqualTo("https://tejdux.com/s/c-spring/maya");
    }

    @Test
    @DisplayName("a coupon with no slug gets the page link, not a URL that 404s")
    void fallsBackToThePageLink() {
        // Section 10.3 records that /s/{slug}/{creator} 404s when no coupon matches. A share kit
        // whose central artifact is a dead link is worse than one with a plainer link that works.
        JsonNode kit = service(page("published", BRAND), coupon(BRAND, null)).forCoupon(BRAND, PAGE, COUPON);

        assertThat(kit.get("link").asText()).isEqualTo("https://tejdux.com/s/c-spring");
    }

    @Test
    @DisplayName("captions are built from the page's words, with no model call")
    void captionsComeFromThePage() {
        JsonNode kit = service(page("published", BRAND), coupon(BRAND, "maya")).forCoupon(BRAND, PAGE, COUPON);

        assertThat(captionFor(kit, "other")).contains("Trail shoes for the wet months");
        assertThat(captionFor(kit, "other")).contains("20% off your first order");
    }

    @Test
    @DisplayName("a page with no media yields no assets rather than a broken image entry")
    void noAssetsWhenThePageHasNone() {
        JsonNode kit = service(page("published", BRAND), coupon(BRAND, "maya")).forCoupon(BRAND, PAGE, COUPON);

        assertThat(kit.get("assets")).isEmpty();
    }
}
