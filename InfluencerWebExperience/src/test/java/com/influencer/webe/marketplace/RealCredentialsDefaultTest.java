package com.influencer.webe.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.marketplace.provider.MockMarketplaceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safe default on the marketplace SPI (roadmap M3.1).
 *
 * <p>This is a one-line rule with an outsized consequence, so it is pinned: a new adapter that
 * never thinks about credential storage must get encryption. If this default ever flips, the next
 * real integration stores its store tokens in the clear and nothing else in the codebase objects.
 */
class RealCredentialsDefaultTest {

    @Test
    @DisplayName("an adapter that says nothing about credentials is protected by default")
    void defaultsToProtected() {
        assertTrue(new SilentProvider().usesRealCredentials(),
                "a new adapter must get encryption by forgetting, not by remembering");
    }

    @Test
    @DisplayName("the mock opts out explicitly")
    void mockOptsOut() {
        // Visible in a diff, which is the point of making it an override rather than a default.
        assertFalse(new MockMarketplaceProvider().usesRealCredentials());
    }

    /** An adapter written by someone who never read {@link MarketplaceProvider#usesRealCredentials}. */
    private static final class SilentProvider implements MarketplaceProvider {
        @Override
        public String key() {
            return "silent";
        }

        @Override
        public String displayName() {
            return "Silent";
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }

        @Override
        public ConnectionResult connect(Map<String, String> credentials) {
            return ConnectionResult.ok("acct", "Silent");
        }

        @Override
        public ExternalCoupon createCoupon(CouponSpec spec, Connection connection) {
            return ExternalCoupon.synced("x");
        }

        @Override
        public void updateCoupon(String externalId, CouponSpec spec, Connection connection) {
        }

        @Override
        public void deactivateCoupon(String externalId, Connection connection) {
        }

        @Override
        public List<OrderEvent> fetchOrders(Instant since, Connection connection) {
            return List.of();
        }

        @Override
        public boolean verifyWebhook(byte[] body, Map<String, String> headers, Connection connection) {
            return false;
        }

        @Override
        public OrderEvent normalizeOrderEvent(JsonNode raw) {
            return new OrderEvent();
        }
    }
}
