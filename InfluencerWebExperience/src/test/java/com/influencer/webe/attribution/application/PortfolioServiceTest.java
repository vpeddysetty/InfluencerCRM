package com.influencer.webe.attribution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.application.BrandAccessPort;
import com.influencer.webe.security.AccountRole;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One view across every client an agency manages (roadmap PR-64).
 *
 * <p>Two behaviours carry the weight, and both are about not stating something false. A brand whose
 * figures could not be read is reported UNAVAILABLE, never as zero — a zero is a claim that the
 * client sold nothing, and an agency acting on it would draw the opposite conclusion from the
 * truth. And the scope comes from the same brand-access answer the switcher uses, so the portfolio
 * cannot show a brand its owner cannot open.
 */
class PortfolioServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID BRAND_A = UUID.randomUUID();
    private static final UUID BRAND_B = UUID.randomUUID();

    /** Answers with a fixed brand list, and records who it was asked about. */
    private static final class StubAccess implements BrandAccessPort {
        private final List<BrandAccess> brands;
        UUID askedAbout;

        StubAccess(List<BrandAccess> brands) {
            this.brands = brands;
        }

        @Override
        public List<BrandAccess> findAccessibleBrandsForPort(UUID userId) {
            askedAbout = userId;
            return brands;
        }
    }

    /** Returns canned analytics per brand; a brand mapped to null throws, standing in for an outage. */
    private static final class StubAnalytics extends AnalyticsService {
        private final Map<UUID, JsonNode> byBrand;
        final List<UUID> asked = new ArrayList<>();

        StubAnalytics(Map<UUID, JsonNode> byBrand) {
            super(null, new ResponseShapeService(MAPPER));
            this.byBrand = byBrand;
        }

        JsonNode byBrandNode(UUID brandId) {
            return byBrand.get(brandId);
        }

        @Override
        public JsonNode influencerRevenue(UUID brandId, LocalDate from, LocalDate to) {
            asked.add(brandId);
            JsonNode result = byBrand.get(brandId);
            if (result == null) {
                throw new IllegalStateException("DAO unreachable for " + brandId);
            }
            return result;
        }
    }

    private JsonNode analyticsFor(String revenue, int orders, String commission, String cost, int creators) {
        ObjectNode out = MAPPER.createObjectNode();
        ObjectNode kpis = out.putObject("kpis");
        kpis.put("revenue", revenue);
        kpis.put("orders", orders);
        kpis.put("commission", commission);
        kpis.put("totalInfluencerCost", cost);
        for (int i = 0; i < creators; i++) {
            out.withArray("byCreator").addObject().put("creatorId", UUID.randomUUID().toString());
        }
        return out;
    }

    private BrandAccessPort.BrandAccess access(UUID brandId, String name) {
        return new BrandAccessPort.BrandAccess(brandId, name, UUID.randomUUID(), "agency",
                AccountRole.OWNER);
    }

    private PortfolioService service(StubAccess access, StubAnalytics analytics) {
        return new PortfolioService(access, analytics, new ResponseShapeService(MAPPER));
    }

    @Test
    @DisplayName("every reachable brand appears, and the totals are the sum of them")
    void aggregatesAcrossBrands() {
        StubAccess access = new StubAccess(List.of(access(BRAND_A, "Aurora"), access(BRAND_B, "Bolt")));
        StubAnalytics analytics = new StubAnalytics(Map.of(
                BRAND_A, analyticsFor("1000.00", 10, "100.00", "500.00", 3),
                BRAND_B, analyticsFor("500.00", 5, "50.00", "250.00", 2)));

        JsonNode out = service(access, analytics).portfolio(USER, null, null);

        assertEquals(2, out.get("brands").size());
        assertEquals("1500.00", out.get("totals").get("revenue").asText());
        assertEquals(15, out.get("totals").get("orders").asInt());
        assertEquals("150.00", out.get("totals").get("commission").asText());
    }

    @Test
    @DisplayName("a brand whose figures cannot be read is UNAVAILABLE, never zero")
    void unreadableBrandIsNotZero() {
        // The failure that would matter. A zero says this client sold nothing; an agency reading it
        // would go and ask why, when the truth is that we could not check.
        StubAccess access = new StubAccess(List.of(access(BRAND_A, "Aurora"), access(BRAND_B, "Bolt")));
        StubAnalytics analytics = new StubAnalytics(Map.of(
                BRAND_A, analyticsFor("1000.00", 10, "100.00", "500.00", 3)));

        JsonNode out = service(access, analytics).portfolio(USER, null, null);

        JsonNode bolt = out.get("brands").get(1);
        assertFalse(bolt.get("available").asBoolean(), "an unreadable brand must be marked unavailable");
        assertFalse(bolt.has("revenue"), "it must not carry a revenue figure at all, not even 0");
        assertEquals(1, out.get("totals").get("brandsUnavailable").asInt());
    }

    @Test
    @DisplayName("one brand's outage does not blank the others")
    void oneOutageDoesNotFailThePage() {
        StubAccess access = new StubAccess(List.of(access(BRAND_A, "Aurora"), access(BRAND_B, "Bolt")));
        StubAnalytics analytics = new StubAnalytics(Map.of(
                BRAND_A, analyticsFor("1000.00", 10, "100.00", "500.00", 3)));

        JsonNode out = service(access, analytics).portfolio(USER, null, null);

        assertTrue(out.get("brands").get(0).get("available").asBoolean());
        assertEquals("1000.00", out.get("totals").get("revenue").asText());
    }

    @Test
    @DisplayName("the scope is the CALLER's brands, asked of the one access rule")
    void scopeComesFromBrandAccess() {
        // Not a brandId parameter a client may choose, and not a second definition of who sees
        // what -- section 5 records that divergence as how a user sees one set of brands and is
        // refused on another.
        StubAccess access = new StubAccess(List.of(access(BRAND_A, "Aurora")));
        StubAnalytics analytics = new StubAnalytics(Map.of(
                BRAND_A, analyticsFor("10.00", 1, "1.00", "5.00", 1)));

        service(access, analytics).portfolio(USER, null, null);

        assertEquals(USER, access.askedAbout);
        assertEquals(List.of(BRAND_A), analytics.asked);
    }

    @Test
    @DisplayName("a user who reaches nothing gets an empty portfolio, not an error")
    void noBrandsIsNotAnError() {
        JsonNode out = service(new StubAccess(List.of()), new StubAnalytics(Map.of()))
                .portfolio(USER, null, null);

        assertEquals(0, out.get("brands").size());
        assertEquals(0, out.get("totals").get("brands").asInt());
        assertEquals("0", out.get("totals").get("revenue").asText());
    }

    @Test
    @DisplayName("portfolio ROI is the ratio of the sums, not the mean of the ratios")
    void roiIsRecomputedFromTotals() {
        // Averaging per-brand ratios gives a number that disagrees with its own parts, and an
        // agency checking the total against the rows would find they did not add up.
        StubAccess access = new StubAccess(List.of(access(BRAND_A, "Aurora"), access(BRAND_B, "Bolt")));
        StubAnalytics analytics = new StubAnalytics(Map.of(
                BRAND_A, analyticsFor("1000.00", 10, "0.00", "100.00", 1),   // 10x
                BRAND_B, analyticsFor("100.00", 1, "0.00", "100.00", 1)));   // 1x

        JsonNode out = service(access, analytics).portfolio(USER, null, null);

        // 1100 / 200 = 5.50, not the 5.5 average of 10 and 1 (which is 5.5 by coincidence of these
        // numbers -- so assert the ratio-of-sums value explicitly).
        assertEquals("5.50", out.get("totals").get("roi").asText());
    }

    @Test
    @DisplayName("ROI is absent rather than infinite when nothing was spent")
    void roiIsNullWithoutCost() {
        StubAccess access = new StubAccess(List.of(access(BRAND_A, "Aurora")));
        StubAnalytics analytics = new StubAnalytics(Map.of(
                BRAND_A, analyticsFor("1000.00", 10, "0.00", "0.00", 1)));

        JsonNode out = service(access, analytics).portfolio(USER, null, null);

        assertTrue(out.get("totals").get("roi").isNull(),
                "revenue with no recorded cost has an ROI nobody can compute");
    }

    @Test
    @DisplayName("a brand row and the totals agree about ROI when nothing was spent")
    void perBrandRoiFollowsTheSameRuleAsTheTotal() {
        // Found by running the stack, not by a unit test. AnalyticsService answers "0.00" (or "∞")
        // for a zero cost, which suits a single-brand dashboard; this class answers null. Copying
        // the per-brand figure straight through put both conventions on one screen -- a row reading
        // 0.00x directly under a total reading "—", for the same situation.
        StubAccess access = new StubAccess(List.of(access(BRAND_A, "Aurora")));
        StubAnalytics analytics = new StubAnalytics(Map.of(
                BRAND_A, analyticsFor("0", 0, "0", "0", 0)));
        // What AnalyticsService would really have said for this brand.
        ((ObjectNode) analytics.byBrandNode(BRAND_A).get("kpis")).put("roi", "0.00");

        JsonNode out = service(access, analytics).portfolio(USER, null, null);

        assertTrue(out.get("brands").get(0).get("roi").isNull(),
                "the row must not report 0.00x while the total above it reports nothing");
        assertTrue(out.get("totals").get("roi").isNull());
    }
}
