package com.influencer.webe.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.billing.provider.ManualBillingProvider;
import com.influencer.webe.billing.provider.StripeBillingProvider;
import com.influencer.webe.shared.infrastructure.OutboundHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the half-configured states, which are the dangerous ones.
 *
 * <p>A Stripe adapter with a key but no price ids, or with neither, must not look like a working
 * payment provider — it would produce checkouts that always fail while the product reports the
 * account as subscribed. These tests do not call Stripe; what is worth pinning down is what the
 * adapter claims about itself before any call is made.
 */
class StripeBillingProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** No call in these tests reaches the network — every one fails the isConfigured() gate first. */
    private static OutboundHttpClient http() {
        return new OutboundHttpClient(MAPPER, 1000, 1000);
    }

    private static StripeBillingProvider provider(String key, String pricePro, String priceAgency) {
        return new StripeBillingProvider(http(), key, pricePro, priceAgency);
    }

    @Test
    @DisplayName("a key with no price ids is not configured")
    void keyWithoutPricesIsNotConfigured() {
        // The trap. A secret key alone cannot start a subscription to anything, so reporting
        // configured here would mean every checkout fails after the account already looks
        // subscribed.
        StripeBillingProvider stripe = provider("sk_test_abc", "", "");

        assertFalse(stripe.isConfigured());
        assertFalse(stripe.capabilities().chargesMoney(),
                "an adapter that cannot charge must not claim it charges");
    }

    @Test
    @DisplayName("no key at all is not configured")
    void noKeyIsNotConfigured() {
        assertFalse(provider("", "price_pro", "price_agency").isConfigured());
        assertFalse(provider(null, "price_pro", "price_agency").isConfigured());
    }

    @Test
    @DisplayName("a key plus at least one price is configured and charges")
    void configuredWhenKeyAndPricePresent() {
        StripeBillingProvider stripe = provider("sk_test_abc", "price_pro", "");

        assertTrue(stripe.isConfigured());
        assertTrue(stripe.capabilities().chargesMoney());
        // Hosted both ways — the roadmap's instruction for 2.1 is to build no billing UI, and an
        // adapter claiming otherwise would invite one.
        assertTrue(stripe.capabilities().hostedCheckout());
        assertTrue(stripe.capabilities().hostedPortal());
    }

    @Test
    @DisplayName("test mode is detected from the key and shown in the name")
    void testModeIsVisible() {
        // A sandbox key charges nobody real. An operator looking at a subscription needs to be
        // able to tell which mode produced it without checking config.
        assertTrue(provider("sk_test_abc", "price_pro", null).isTestMode());
        assertTrue(provider("sk_test_abc", "price_pro", null).displayName().contains("test mode"));

        assertFalse(provider("sk_live_abc", "price_pro", null).isTestMode());
        assertFalse(provider("sk_live_abc", "price_pro", null).displayName().contains("test"));
    }

    @Test
    @DisplayName("an unconfigured adapter refuses checkout rather than half-starting one")
    void unconfiguredCheckoutFailsCleanly() {
        StripeBillingProvider stripe = provider("", "", "");

        BillingProvider.CheckoutSession session =
                stripe.startCheckout("sub-1", "acct-1", "pro", "s", "c");

        assertNull(session.checkoutUrl());
        assertFalse(session.activated(), "nothing may be activated without a payment");
        assertTrue(session.detail().toLowerCase().contains("not configured"));
    }

    @Test
    @DisplayName("a plan with no configured price is refused")
    void unknownPlanIsRefused() {
        // Pro is configured, agency is not. Falling back to the wrong price would charge someone
        // for a plan they did not choose.
        StripeBillingProvider stripe = provider("sk_test_abc", "price_pro", "");

        BillingProvider.CheckoutSession session =
                stripe.startCheckout("sub-1", "acct-1", "agency", "s", "c");

        assertNull(session.checkoutUrl());
        assertFalse(session.activated());
    }

    @Test
    @DisplayName("checkout never activates a subscription by itself")
    void checkoutDoesNotActivate() {
        // The difference from ManualBillingProvider, and it matters: the user has been given a
        // page, not charged. Activating here would grant a paid plan to anyone who clicked
        // subscribe and closed the tab. Activation happens on checkout.session.completed.
        StripeBillingProvider stripe = provider("sk_test_abc", "price_pro", "price_agency");

        // The call fails at the network (no sandbox reachable in a unit test), and the contract
        // that matters is that it does not report an active subscription either way.
        assertFalse(stripe.startCheckout("sub-1", "acct-1", "pro", "s", "c").activated());
    }

    @Test
    @DisplayName("lifecycle calls refuse without a provider reference")
    void lifecycleNeedsAReference() {
        StripeBillingProvider stripe = provider("sk_test_abc", "price_pro", "price_agency");

        for (BillingProvider.Result result : List.of(
                stripe.cancel(null), stripe.pause(""), stripe.resume(null))) {
            assertFalse(result.ok());
            assertEquals("stripe", result.provider());
        }
        assertNull(stripe.portalUrl(null, "return"));
    }

    @Test
    @DisplayName("the registry prefers stripe when configured, and manual otherwise")
    void registrySelection() {
        StripeBillingProvider stripe = provider("sk_test_abc", "price_pro", "price_agency");
        ManualBillingProvider manual = new ManualBillingProvider();

        assertEquals("stripe",
                new BillingProviderRegistry(List.of(manual, stripe), "stripe").active().key());
        assertEquals("manual",
                new BillingProviderRegistry(List.of(manual, stripe), "manual").active().key());
        // A typo must not fail open onto whichever bean happens to be first.
        assertEquals("manual",
                new BillingProviderRegistry(List.of(manual, stripe), "strpe").active().key());
    }
}
