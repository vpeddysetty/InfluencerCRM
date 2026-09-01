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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Counting the view log (roadmap PR-57).
 *
 * <p>The behaviour worth pinning is not the arithmetic — it is what the counts must REFUSE to do:
 * count another campaign's coupon, count a view older than the window, or claim a number for a page
 * that has no codes to attribute one to. Those are the ways a report becomes confidently wrong,
 * which is worse than absent, because a brand would spend against it.
 */
class LandingAnalyticsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CAMPAIGN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String COUPON_A = "33333333-3333-3333-3333-333333333333";
    private static final String COUPON_B = "44444444-4444-4444-4444-444444444444";
    private static final String OTHER_COUPON = "55555555-5555-5555-5555-555555555555";
    private static final String CREATOR_A = "66666666-6666-6666-6666-666666666666";
    private static final String CREATOR_B = "77777777-7777-7777-7777-777777777777";

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

        StubDao with(String path, JsonNode response) {
            responses.put(path, response);
            return this;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            return responses.get(path);
        }
    }

    private ObjectNode coupon(String id, String creatorId, String code) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("creatorId", creatorId);
        node.put("code", code);
        return node;
    }

    private ObjectNode view(String couponId, Instant at) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("campaignCodeId", couponId);
        node.put("occurredAt", at.toString());
        return node;
    }

    private LandingAnalyticsService service(ArrayNode coupons, ArrayNode views) {
        StubDao dao = new StubDao()
                .with("/influencer-campaign-codes", coupons)
                .with("/landing-page-views", views);
        return new LandingAnalyticsService(dao, new ResponseShapeService(MAPPER));
    }

    @Test
    @DisplayName("it counts views per creator, busiest first")
    void countsPerCreator() {
        ArrayNode coupons = MAPPER.createArrayNode();
        coupons.add(coupon(COUPON_A, CREATOR_A, "MAYA10"));
        coupons.add(coupon(COUPON_B, CREATOR_B, "SAM10"));

        Instant now = Instant.now();
        ArrayNode views = MAPPER.createArrayNode();
        views.add(view(COUPON_B, now));
        views.add(view(COUPON_A, now));
        views.add(view(COUPON_A, now));
        views.add(view(COUPON_A, now));

        JsonNode out = service(coupons, views).forCampaign(BRAND, CAMPAIGN, 30);

        assertThat(out.get("totalViews").asLong()).isEqualTo(4);
        assertThat(out.get("byCreator").get(0).get("creatorId").asText()).isEqualTo(CREATOR_A);
        assertThat(out.get("byCreator").get(0).get("views").asLong()).isEqualTo(3);
        assertThat(out.get("byCreator").get(0).get("code").asText()).isEqualTo("MAYA10");
        assertThat(out.get("byCreator").get(1).get("views").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("a view on another campaign's coupon is never counted")
    void ignoresOtherCampaigns() {
        // The view log is ONE table across every brand and campaign. A report that counted a
        // neighbour's traffic would be confidently wrong, and a brand would spend against it.
        ArrayNode coupons = MAPPER.createArrayNode();
        coupons.add(coupon(COUPON_A, CREATOR_A, "MAYA10"));

        ArrayNode views = MAPPER.createArrayNode();
        views.add(view(COUPON_A, Instant.now()));
        views.add(view(OTHER_COUPON, Instant.now()));
        views.add(view(OTHER_COUPON, Instant.now()));

        JsonNode out = service(coupons, views).forCampaign(BRAND, CAMPAIGN, 30);

        assertThat(out.get("totalViews").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("views older than the window are excluded")
    void respectsTheWindow() {
        ArrayNode coupons = MAPPER.createArrayNode();
        coupons.add(coupon(COUPON_A, CREATOR_A, "MAYA10"));

        ArrayNode views = MAPPER.createArrayNode();
        views.add(view(COUPON_A, Instant.now()));
        views.add(view(COUPON_A, Instant.now().minus(40, ChronoUnit.DAYS)));

        JsonNode out = service(coupons, views).forCampaign(BRAND, CAMPAIGN, 30);

        assertThat(out.get("totalViews").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("a page with no codes says so rather than reporting zero views")
    void noCouponsIsNotZeroViews() {
        // "Nothing to show" and "nobody came" are different claims, and only one of them is
        // honest here: a coupon-less page is still visited, those views just cannot be attributed.
        JsonNode out = service(MAPPER.createArrayNode(), MAPPER.createArrayNode())
                .forCampaign(BRAND, CAMPAIGN, 30);

        assertThat(out.get("totalViews").asLong()).isZero();
        assertThat(out.get("note").asText()).contains("cannot be attributed");
        assertThat(out.get("byCreator")).isEmpty();
    }

    @Test
    @DisplayName("days are ascending, so a chart needs no sorting")
    void daysAscend() {
        ArrayNode coupons = MAPPER.createArrayNode();
        coupons.add(coupon(COUPON_A, CREATOR_A, "MAYA10"));

        ArrayNode views = MAPPER.createArrayNode();
        views.add(view(COUPON_A, Instant.now()));
        views.add(view(COUPON_A, Instant.now().minus(2, ChronoUnit.DAYS)));
        views.add(view(COUPON_A, Instant.now().minus(1, ChronoUnit.DAYS)));

        JsonNode out = service(coupons, views).forCampaign(BRAND, CAMPAIGN, 30);
        JsonNode byDay = out.get("byDay");

        assertThat(byDay).hasSize(3);
        String first = byDay.get(0).get("date").asText();
        String last = byDay.get(2).get("date").asText();
        assertThat(first.compareTo(last)).isNegative();
    }

    @Test
    @DisplayName("an unreadable DAO reports nothing rather than failing the request")
    void daoFailureDegrades() {
        // Same rule as BriefEnricher: analytics is an improvement on the page, never a gate on it.
        StubDao dao = new StubDao();   // every read returns null
        LandingAnalyticsService service = new LandingAnalyticsService(dao, new ResponseShapeService(MAPPER));

        JsonNode out = service.forCampaign(BRAND, CAMPAIGN, 30);

        assertThat(out.get("totalViews").asLong()).isZero();
    }

    @Test
    @DisplayName("the window is clamped, so a caller cannot ask for all of history")
    void windowIsClamped() {
        ArrayNode coupons = MAPPER.createArrayNode();
        coupons.add(coupon(COUPON_A, CREATOR_A, "MAYA10"));

        LandingAnalyticsService service = service(coupons, MAPPER.createArrayNode());

        assertThat(service.forCampaign(BRAND, CAMPAIGN, null).get("windowDays").asInt()).isEqualTo(30);
        assertThat(service.forCampaign(BRAND, CAMPAIGN, 0).get("windowDays").asInt()).isEqualTo(30);
        assertThat(service.forCampaign(BRAND, CAMPAIGN, 9999).get("windowDays").asInt()).isEqualTo(365);
        assertThat(service.forCampaign(BRAND, CAMPAIGN, 7).get("windowDays").asInt()).isEqualTo(7);
    }
}
