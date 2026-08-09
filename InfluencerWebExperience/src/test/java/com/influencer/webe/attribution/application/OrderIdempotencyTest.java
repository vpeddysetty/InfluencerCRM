package com.influencer.webe.attribution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.marketplace.MarketplaceProviderRegistry;
import com.influencer.webe.marketplace.OrderEvent;
import com.influencer.webe.marketplace.provider.MockMarketplaceProvider;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import com.influencer.platform.workload.WorkloadTokenIssuer;
import com.influencer.webe.marketplace.Connection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order ingestion must be idempotent (M3 item 0b).
 *
 * <p>Shopify retries {@code orders/paid} until it receives a 2xx, so the same order arrives twice
 * as a matter of routine rather than as an anomaly. The in-code check
 * ({@code findExistingAttribution}) catches the sequential case; the unique index added in
 * {@code 2026_08_09_m3_order_attribution_idempotency.sql} catches the concurrent one.
 *
 * <p>These tests pin the half that lives in application code: what happens when the DAO refuses the
 * insert with a 409 because a concurrent delivery won the race. Getting this wrong is expensive in
 * a specific way — a 5xx tells Shopify to retry, and the retry hits the same constraint, so a guard
 * that is working correctly presents as an endpoint that is permanently broken.
 */
class OrderIdempotencyTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String COUPON_ID = UUID.randomUUID().toString();
    private static final String EXISTING_ATTRIBUTION_ID = UUID.randomUUID().toString();

    @Test
    @DisplayName("losing the insert race reports a duplicate, not an error")
    void concurrentDuplicateIsReportedAsDuplicate() {
        // The winner's row is invisible to the first read (that is what makes it a race) but
        // present on the re-read after the constraint fires.
        StubDao dao = new StubDao(/* rowsVisibleUpFront */ false, /* rowsVisibleAfterConflict */ true);

        JsonNode result = serviceWith(dao).attribute(BRAND, "mock", orderEvent());

        assertEquals("duplicate", result.get("outcome").asText());
        assertEquals(EXISTING_ATTRIBUTION_ID, result.get("attributionId").asText(),
                "the caller should be pointed at the row the winner wrote");
    }

    @Test
    @DisplayName("a duplicate accrues no second commission")
    void duplicateDoesNotAccrueCommission() {
        // The point of the whole exercise. A second commission row for one sale becomes a real
        // payout to a creator for money the brand only earned once.
        StubDao dao = new StubDao(false, true);

        serviceWith(dao).attribute(BRAND, "mock", orderEvent());

        assertFalse(dao.posted.contains("/influencer-commissions"),
                "a duplicate delivery must not accrue a second commission");
    }

    @Test
    @DisplayName("a duplicate still succeeds when the winner's row is not yet readable")
    void duplicateSucceedsEvenIfReReadIsEmpty() {
        // The constraint has already proven a row exists. If the re-read cannot see it — a read
        // that has not caught up — the answer is still "duplicate": retrying is the one thing that
        // definitely cannot help, because the same constraint will refuse the same insert.
        StubDao dao = new StubDao(false, false);

        JsonNode result = serviceWith(dao).attribute(BRAND, "mock", orderEvent());

        assertEquals("duplicate", result.get("outcome").asText());
        assertNotNull(result.get("reason"), "an unresolvable duplicate should say why");
        assertFalse(dao.posted.contains("/influencer-commissions"));
    }

    @Test
    @DisplayName("a non-conflict DAO failure still propagates")
    void otherFailuresAreNotSwallowed() {
        // The catch is narrow on purpose. Treating every DAO failure as "already done" would turn
        // an outage into silently dropped revenue — the sale would be acknowledged to Shopify and
        // never recorded anywhere.
        StubDao dao = new StubDao(false, false);
        dao.failWith = HttpStatus.INTERNAL_SERVER_ERROR;

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> serviceWith(dao).attribute(BRAND, "mock", orderEvent()));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, thrown.getStatusCode());
    }

    @Test
    @DisplayName("the sequential duplicate is still caught by the read, before any write")
    void sequentialDuplicateNeedsNoConstraint() {
        // The fast path still works and is still the common case: when the prior row IS visible,
        // nothing is posted at all.
        StubDao dao = new StubDao(true, true);

        JsonNode result = serviceWith(dao).attribute(BRAND, "mock", orderEvent());

        assertEquals("duplicate", result.get("outcome").asText());
        assertEquals(EXISTING_ATTRIBUTION_ID, result.get("attributionId").asText());
        assertTrue(dao.posted.isEmpty(), "the fast path should not write anything");
    }

    // ---- fixtures ------------------------------------------------------

    private OrderEvent orderEvent() {
        OrderEvent event = new OrderEvent();
        event.setExternalOrderId("shopify-order-1001");
        event.setExternalOrderLineId(null);   // order-level: the null-line case the partial index covers
        event.setCouponCode("ADA10");
        event.setSaleAmount(new BigDecimal("100.00"));
        event.setDiscountAmount(new BigDecimal("10.00"));
        event.setCurrency("USD");
        event.setStatus("purchase");
        return event;
    }

    private AttributionService serviceWith(DaoGatewayClient dao) {
        MarketplaceProviderRegistry registry =
                new MarketplaceProviderRegistry(List.of(new MockMarketplaceProvider()));
        return new AttributionService(dao, new ResponseShapeService(mapper), registry);
    }

    /**
     * A DAO stub that can refuse a write the way the unique index does.
     *
     * <p>Subclassed rather than mocked, matching {@code AnalyticsWindowTest}: Mockito's bundled
     * bytecode engine cannot mock {@code DaoGatewayClient} under Java 26.
     */
    private static final class StubDao extends DaoGatewayClient {
        private final boolean rowsVisibleUpFront;
        private final boolean rowsVisibleAfterConflict;
        private boolean conflicted;

        /** Non-null makes the attribution insert fail with this status instead of 409. */
        private HttpStatus failWith;

        private final List<String> posted = new ArrayList<>();

        StubDao(boolean rowsVisibleUpFront, boolean rowsVisibleAfterConflict) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, new WorkloadTokenIssuer("test", "", ""));
            this.rowsVisibleUpFront = rowsVisibleUpFront;
            this.rowsVisibleAfterConflict = rowsVisibleAfterConflict;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.equals("/influencer-campaign-codes")) {
                return couponRows();
            }
            if (path.equals("/influencer-sale-attributions")) {
                boolean visible = conflicted ? rowsVisibleAfterConflict : rowsVisibleUpFront;
                return visible ? attributionRows() : mapper.createArrayNode();
            }
            return mapper.createArrayNode();
        }

        @Override
        public JsonNode post(String path, JsonNode payload) {
            if (path.equals("/influencer-sale-attributions")) {
                conflicted = true;
                HttpStatus status = failWith == null ? HttpStatus.CONFLICT : failWith;
                // What DaoGatewayClient.toGatewayException produces for a DAO 409: the unique
                // violation surfaces as CONFLICT through the same path a real insert would take.
                throw new ResponseStatusException(status, "DAO POST " + path + " failed");
            }
            posted.add(path);
            ObjectNode saved = mapper.createObjectNode();
            saved.put("id", UUID.randomUUID().toString());
            return saved;
        }

        private ArrayNode couponRows() {
            ArrayNode rows = mapper.createArrayNode();
            ObjectNode coupon = mapper.createObjectNode();
            coupon.put("id", COUPON_ID);
            coupon.put("code", "ADA10");
            coupon.put("campaignId", UUID.randomUUID().toString());
            coupon.put("creatorId", UUID.randomUUID().toString());
            coupon.put("commissionType", "percent");
            coupon.put("commissionValue", "10");
            rows.add(coupon);
            return rows;
        }

        private ArrayNode attributionRows() {
            ArrayNode rows = mapper.createArrayNode();
            ObjectNode row = mapper.createObjectNode();
            row.put("id", EXISTING_ATTRIBUTION_ID);
            row.put("orderId", "shopify-order-1001");
            // No orderLineId: matches the order-level event, which is what the null-line partial
            // index guards and what findExistingAttribution matches with its null branch.
            rows.add(row);
            return rows;
        }
    }
}
