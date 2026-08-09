package com.influencer.webe.attribution;

import com.influencer.webe.marketplace.Connection;
import com.influencer.webe.marketplace.provider.MockMarketplaceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order webhook's authentication contract.
 *
 * <p>The endpoint previously took {@code brandId} from the caller's query string and never called
 * {@code verifyWebhook}, so anyone who knew the URL could post a fabricated order naming any brand
 * and any coupon code — into attribution, which accrues commission, which becomes a payout.
 *
 * <p>These tests pin the SPI contract the fix relies on. The controller's own wiring is exercised
 * by the e2e journeys against the running stack.
 */
class WebhookAuthTest {

    @Test
    @DisplayName("the mock provider trusts everything, and that is why it must stay a dev double")
    void theMockTrustsEverything() {
        // Recorded rather than assumed: MockMarketplaceProvider.verifyWebhook returns true
        // unconditionally. That is correct for a test double and catastrophic for a real adapter,
        // which is why usesRealCredentials() is false on it and true by default everywhere else.
        MockMarketplaceProvider mock = new MockMarketplaceProvider();
        Connection connection = new Connection("id", "mock", "acct", Map.of(), null);

        assertTrue(mock.verifyWebhook("anything".getBytes(), Map.of(), connection));
        assertFalse(mock.usesRealCredentials());
    }

    @Test
    @DisplayName("a real adapter must not inherit a trusting default")
    void verifyWebhookHasNoDefault() throws Exception {
        // verifyWebhook is abstract on the SPI — there is no default implementation an adapter
        // author could forget to override into something permissive. If a `default` is ever added
        // returning true, every future adapter silently accepts forged webhooks.
        var method = com.influencer.webe.marketplace.MarketplaceProvider.class
                .getMethod("verifyWebhook", byte[].class, Map.class, Connection.class);

        assertFalse(method.isDefault(), "verifyWebhook must stay abstract on the SPI");
    }
}
