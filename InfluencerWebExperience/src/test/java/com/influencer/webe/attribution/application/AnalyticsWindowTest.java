package com.influencer.webe.attribution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import com.influencer.platform.workload.WorkloadTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the analytics date window.
 *
 * <p>These cover the arithmetic that decides which rows count toward a KPI. Getting it wrong does
 * not throw — it silently reports a different number, which is the worst failure mode a revenue
 * dashboard has.
 *
 * <p>The flat-fee cases exist because of a real bug found by end-to-end testing: {@code agreedFee}
 * was summed across every workflow card regardless of the requested window, so a $1000 fee was
 * charged in full to "last 7 days" as well as to all-time. Against $300 of 7-day revenue that
 * reported ROI 0.29x while all-time reported 2.74x, from one unchanged fee — the narrower the
 * window, the worse a profitable creator looked.
 */
class AnalyticsWindowTest {

    private static final UUID BRAND = UUID.randomUUID();
    private static final String CREATOR = "11111111-1111-1111-1111-111111111111";

    private final ObjectMapper mapper = new ObjectMapper();

    /** An attribution row shaped the way the DAO returns one. */
    private ObjectNode attribution(String orderId, String occurredAt, String sale, String commission) {
        ObjectNode n = mapper.createObjectNode();
        n.put("orderId", orderId);
        n.put("creatorId", CREATOR);
        n.put("platform", "instagram");
        n.put("status", "attributed");
        n.put("saleAmount", sale);
        n.put("discountAmount", "0");
        n.put("commissionAmount", commission);
        if (occurredAt != null) {
            n.put("occurredAt", occurredAt);
        }
        return n;
    }

    private ObjectNode card(String createdAt, String agreedFee) {
        ObjectNode n = mapper.createObjectNode();
        n.put("creatorId", CREATOR);
        n.put("agreedFee", agreedFee);
        if (createdAt != null) {
            n.put("createdAt", createdAt);
        }
        return n;
    }

    /**
     * A hand-written DAO stub that answers by path.
     *
     * <p>Deliberately a subclass rather than a Mockito mock: Mockito's bundled bytecode engine
     * cannot mock this class under Java 26 ("Mockito cannot mock this class"), and a stub this
     * small does not need a framework. It also keeps the test honest about which three paths
     * analytics actually reads.
     */
    private static final class StubDao extends DaoGatewayClient {
        private final Map<String, JsonNode> byPath;

        StubDao(Map<String, JsonNode> byPath) {
            // The superclass constructor calls factory.create(), so the factory is overridden to
            // return null rather than build a real TLS client. Nothing here opens a socket, and
            // the only method this test reaches is get(), overridden below.
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            },
            // No signing key: the stub issues no workload token, which is the correct shape for a
            // test that never reaches the network.
            new WorkloadTokenIssuer("test", "", ""));
            this.byPath = byPath;
        }

        /** What each path was asked, so a test can assert the range reached the DAO (OP-39). */
        final Map<String, Map<String, String>> queries = new java.util.LinkedHashMap<>();

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            queries.put(path, query == null ? Map.of() : new java.util.LinkedHashMap<>(query));
            return byPath.get(path);
        }
    }

    /** Wires a service whose DAO returns the given rows. */
    private AnalyticsService serviceWith(ArrayNode attributions, ArrayNode cards) {
        ArrayNode creators = mapper.createArrayNode();
        ObjectNode creator = mapper.createObjectNode();
        creator.put("id", CREATOR);
        creator.put("name", "Ada Lovelace");
        creators.add(creator);

        DaoGatewayClient dao = new StubDao(Map.of(
                "/influencer-sale-attributions", attributions,
                "/creators", creators,
                "/workflow-cards", cards));
        return new AnalyticsService(dao, new ResponseShapeService(mapper));
    }

    private String revenue(JsonNode result) {
        return result.get("kpis").get("revenue").asText();
    }

    private String cost(JsonNode result) {
        return result.get("kpis").get("totalInfluencerCost").asText();
    }

    @Test
    @DisplayName("only orders inside the window count toward revenue")
    void windowsRevenue() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("IN", "2026-08-05T12:00:00Z", "100.00", "10.00"));
        rows.add(attribution("OUT", "2026-01-05T12:00:00Z", "900.00", "90.00"));

        JsonNode ranged = serviceWith(rows, mapper.createArrayNode())
                .influencerRevenue(BRAND, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7));

        assertEquals("100.00", revenue(ranged), "the January order must not count toward an August window");
    }

    @Test
    @DisplayName("the end date is inclusive of the whole day")
    void endDateIsInclusive() {
        ArrayNode rows = mapper.createArrayNode();
        // Late on the final day. A naive `isBefore(to)` against midnight would drop this.
        rows.add(attribution("LATE", "2026-08-07T23:59:30Z", "100.00", "10.00"));

        JsonNode ranged = serviceWith(rows, mapper.createArrayNode())
                .influencerRevenue(BRAND, LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 7));

        assertEquals("100.00", revenue(ranged), "an order at 23:59 on the end date is inside the window");
    }

    @Test
    @DisplayName("the start date includes the first moment of the day")
    void startDateIsInclusive() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("EARLY", "2026-08-01T00:00:30Z", "100.00", "10.00"));

        JsonNode ranged = serviceWith(rows, mapper.createArrayNode())
                .influencerRevenue(BRAND, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7));

        assertEquals("100.00", revenue(ranged));
    }

    @Test
    @DisplayName("an open range counts everything, including rows with no occurredAt")
    void openRangeKeepsUndatedRows() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("DATED", "2026-08-05T12:00:00Z", "100.00", "10.00"));
        rows.add(attribution("UNDATED", null, "50.00", "5.00"));

        JsonNode all = serviceWith(rows, mapper.createArrayNode()).influencerRevenue(BRAND);

        assertEquals("150.00", revenue(all), "the default view must not silently drop undated rows");
    }

    @Test
    @DisplayName("a bounded range drops rows with no occurredAt")
    void boundedRangeDropsUndatedRows() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("DATED", "2026-08-05T12:00:00Z", "100.00", "10.00"));
        rows.add(attribution("UNDATED", null, "50.00", "5.00"));

        JsonNode ranged = serviceWith(rows, mapper.createArrayNode())
                .influencerRevenue(BRAND, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7));

        assertEquals("100.00", revenue(ranged),
                "a row with no date cannot be proven to belong in a narrow window");
    }

    @Test
    @DisplayName("a flat fee outside the window is not charged to it")
    void flatFeeIsWindowed() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("IN", "2026-08-05T12:00:00Z", "300.00", "36.00"));
        ArrayNode cards = mapper.createArrayNode();
        cards.add(card("2026-03-15T12:00:00Z", "1000.00"));

        JsonNode ranged = serviceWith(rows, cards)
                .influencerRevenue(BRAND, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7));

        // Commission only. Before the fix this was 1036.0 and ROI read 0.29x on a creator whose
        // real 7-day ROI was 8.33x.
        assertEquals("36.00", cost(ranged), "a fee agreed in March is not a cost incurred in August");
    }

    @Test
    @DisplayName("a flat fee inside the window is charged to it")
    void flatFeeInsideWindowCounts() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("IN", "2026-03-20T12:00:00Z", "300.00", "36.00"));
        ArrayNode cards = mapper.createArrayNode();
        cards.add(card("2026-03-15T12:00:00Z", "1000.00"));

        JsonNode ranged = serviceWith(rows, cards)
                .influencerRevenue(BRAND, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertEquals("1036.00", cost(ranged), "the fee belongs to the window it was agreed in");
    }

    @Test
    @DisplayName("all-time still includes every flat fee")
    void flatFeeCountsForAllTime() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("IN", "2026-08-05T12:00:00Z", "300.00", "36.00"));
        ArrayNode cards = mapper.createArrayNode();
        cards.add(card("2026-03-15T12:00:00Z", "1000.00"));

        JsonNode all = serviceWith(rows, cards).influencerRevenue(BRAND);

        assertEquals("1036.00", cost(all), "windowing must not change the all-time total");
    }

    @Test
    @DisplayName("an inverted range returns nothing rather than throwing")
    void invertedRangeIsEmpty() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("IN", "2026-08-05T12:00:00Z", "100.00", "10.00"));

        JsonNode ranged = serviceWith(rows, mapper.createArrayNode())
                .influencerRevenue(BRAND, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 1, 1));

        assertEquals("0", revenue(ranged));
    }

    @Test
    @DisplayName("an unparseable timestamp is dropped, not treated as epoch")
    void unparseableTimestampIsDropped() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(attribution("GOOD", "2026-08-05T12:00:00Z", "100.00", "10.00"));
        rows.add(attribution("BAD", "not-a-timestamp", "900.00", "90.00"));

        JsonNode ranged = serviceWith(rows, mapper.createArrayNode())
                .influencerRevenue(BRAND, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7));

        assertEquals("100.00", revenue(ranged), "a corrupt date must not silently land at 1970");
    }

    @Test
    @DisplayName("the date range is sent TO the DAO, not applied after fetching everything")
    void theRangeReachesTheDao() {
        // OP-39. This used to fetch every attribution a brand had ever had and filter in Java --
        // O(all rows ever) per render, degrading as a customer uses the product. The window has to
        // arrive as a query parameter or the fetch is unbounded however well the filtering works.
        StubDao dao = new StubDao(Map.of(
                "/influencer-sale-attributions", mapper.createArrayNode(),
                "/creators", mapper.createArrayNode(),
                "/workflow-cards", mapper.createArrayNode()));
        AnalyticsService service = new AnalyticsService(dao, new ResponseShapeService(mapper));

        service.influencerRevenue(BRAND, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        Map<String, String> asked = dao.queries.get("/influencer-sale-attributions");
        assertNotNull(asked.get("from"), "the window's start must reach the DAO");
        assertNotNull(asked.get("until"), "the window's end must reach the DAO");
        // Half-open: `until` is the start of the day AFTER the inclusive end date, so the 31st is
        // included in full. A closed bound would silently drop a day's sales from every report.
        assertTrue(asked.get("until").startsWith("2026-09-01"),
                "until must be the day AFTER the inclusive end date, got " + asked.get("until"));
        assertTrue(asked.get("from").startsWith("2026-08-01"),
                "from must be the inclusive start date, got " + asked.get("from"));
    }

    @Test
    @DisplayName("an all-time report asks for everything rather than guessing a bound")
    void allTimeSendsNoRange() {
        // One bound alone is ambiguous, and inventing the other would silently change the question.
        StubDao dao = new StubDao(Map.of(
                "/influencer-sale-attributions", mapper.createArrayNode(),
                "/creators", mapper.createArrayNode(),
                "/workflow-cards", mapper.createArrayNode()));
        AnalyticsService service = new AnalyticsService(dao, new ResponseShapeService(mapper));

        service.influencerRevenue(BRAND, null, null);

        assertNull(dao.queries.get("/influencer-sale-attributions").get("from"),
                "an all-time report must not invent a bound");
    }
}
