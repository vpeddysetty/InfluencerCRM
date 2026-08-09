package com.influencer.webe.marketplace;

/**
 * Feature flags a {@link MarketplaceProvider} may support. The UI reads a
 * provider's capability set to gray out unsupported actions per connected store,
 * so core code never special-cases a marketplace.
 */
public enum Capability {
    /** Provider can create/update/deactivate discount codes via its API. */
    CREATE_COUPON,
    /** Provider pushes order events to our webhook endpoint. */
    WEBHOOK_ORDERS,
    /** Provider supports pulling orders since a cursor (reconciliation fallback). */
    POLL_ORDERS,
    /** Provider supports affiliate/tracking links. */
    AFFILIATE_LINK
}
