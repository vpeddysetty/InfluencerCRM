package com.influencer.webe.marketplace;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service Provider Interface for an external shopping marketplace (Shopify,
 * Shopigy, WooCommerce, a Mock, ...). Adding a marketplace = implement this as a
 * Spring {@code @Component}; {@link MarketplaceProviderRegistry} auto-discovers it.
 *
 * Core code (coupon push, attribution, dashboard) only ever talks to this
 * interface and the canonical DTOs — never a vendor API directly. Adapters must
 * declare their {@link Capability} set so the UI can gate unsupported features.
 */
public interface MarketplaceProvider {

    /** Stable key used in URLs, the DB, and the registry (e.g. "shopify", "mock"). */
    String key();

    /** Human-readable name for the UI. */
    String displayName();

    /** Which features this adapter supports. */
    Set<Capability> capabilities();

    /**
     * Validate credentials and resolve the external account. Called before a
     * {@code marketplace_connections} row is persisted.
     */
    ConnectionResult connect(Map<String, String> credentials);

    /**
     * Create a coupon in the marketplace. Only called when {@link #capabilities()}
     * contains {@link Capability#CREATE_COUPON}.
     */
    ExternalCoupon createCoupon(CouponSpec spec, Connection connection);

    /** Update an existing external coupon. */
    void updateCoupon(String externalId, CouponSpec spec, Connection connection);

    /** Deactivate an external coupon. */
    void deactivateCoupon(String externalId, Connection connection);

    /**
     * Pull orders since the given watermark (reconciliation fallback). Only called
     * when {@link #capabilities()} contains {@link Capability#POLL_ORDERS}.
     */
    List<OrderEvent> fetchOrders(Instant since, Connection connection);

    /**
     * Verify an inbound webhook's authenticity. Only relevant when
     * {@link #capabilities()} contains {@link Capability#WEBHOOK_ORDERS}.
     */
    boolean verifyWebhook(byte[] body, Map<String, String> headers, Connection connection);

    /** Translate a raw vendor webhook/poll payload into a canonical {@link OrderEvent}. */
    OrderEvent normalizeOrderEvent(JsonNode raw);
}
