package com.influencer.webe.marketplace.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.marketplace.Capability;
import com.influencer.webe.marketplace.Connection;
import com.influencer.webe.marketplace.ConnectionResult;
import com.influencer.webe.marketplace.CouponSpec;
import com.influencer.webe.marketplace.ExternalCoupon;
import com.influencer.webe.marketplace.MarketplaceProvider;
import com.influencer.webe.marketplace.OrderEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory marketplace adapter implementing the full SPI. Lets the coupon-push,
 * attribution, and dashboard flows be built and tested before any real vendor API
 * exists. "Connects" with any non-blank apiKey; stores created coupons in memory;
 * can synthesize order events (used by the Phase 3 attribution path).
 *
 * NOTE: in-memory state is per-process and lost on restart — this is a dev/test
 * double, not a durable store.
 */
@Component
public class MockMarketplaceProvider implements MarketplaceProvider {
    private final Map<String, CouponSpec> couponsByExternalId = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    @Override
    public String key() {
        return "mock";
    }

    @Override
    public String displayName() {
        return "Mock Marketplace (dev)";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CREATE_COUPON, Capability.WEBHOOK_ORDERS,
                Capability.POLL_ORDERS, Capability.AFFILIATE_LINK);
    }

    @Override
    public ConnectionResult connect(Map<String, String> credentials) {
        String apiKey = credentials == null ? null : credentials.get("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            return ConnectionResult.failed("Mock marketplace requires a non-blank apiKey");
        }
        String shop = credentials.getOrDefault("shop", "mock-shop");
        return ConnectionResult.ok("mock-acct-" + shop, "Mock: " + shop);
    }

    @Override
    public ExternalCoupon createCoupon(CouponSpec spec, Connection connection) {
        String externalId = "mock-" + sequence.incrementAndGet();
        couponsByExternalId.put(externalId, spec);
        return ExternalCoupon.synced(externalId);
    }

    @Override
    public void updateCoupon(String externalId, CouponSpec spec, Connection connection) {
        couponsByExternalId.put(externalId, spec);
    }

    @Override
    public void deactivateCoupon(String externalId, Connection connection) {
        couponsByExternalId.remove(externalId);
    }

    @Override
    public List<OrderEvent> fetchOrders(Instant since, Connection connection) {
        // A durable adapter would return orders after `since`. The mock keeps no
        // order history; synthetic orders arrive via webhook simulation in Phase 3.
        return new ArrayList<>();
    }

    @Override
    public boolean verifyWebhook(byte[] body, Map<String, String> headers, Connection connection) {
        // The mock trusts everything; a real adapter verifies an HMAC signature.
        return true;
    }

    @Override
    public OrderEvent normalizeOrderEvent(JsonNode raw) {
        OrderEvent event = new OrderEvent();
        if (raw == null) {
            return event;
        }
        event.setExternalOrderId(text(raw, "orderId"));
        event.setExternalOrderLineId(text(raw, "orderLineId"));
        event.setCouponCode(text(raw, "code"));
        event.setCustomerExternalId(text(raw, "customerId"));
        event.setCurrency(raw.hasNonNull("currency") ? raw.get("currency").asText() : "USD");
        event.setStatus(raw.hasNonNull("status") ? raw.get("status").asText() : "purchase");
        if (raw.hasNonNull("saleAmount")) {
            event.setSaleAmount(new BigDecimal(raw.get("saleAmount").asText()));
        }
        if (raw.hasNonNull("discountAmount")) {
            event.setDiscountAmount(new BigDecimal(raw.get("discountAmount").asText()));
        }
        event.setOccurredAt(Instant.now());
        return event;
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
