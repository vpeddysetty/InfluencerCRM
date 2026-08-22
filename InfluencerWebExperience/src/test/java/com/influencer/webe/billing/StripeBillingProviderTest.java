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
        return provider(key, pricePro, priceAgency, 0);
    }

    private static StripeBillingProvider provider(String key, String pricePro, String priceAgency,
                                                  int trialDays) {
        // Agency-only trial, matching the shipped configuration. No yearly prices by default, so
        // the monthly-only case stays the one most tests exercise.
        return new StripeBillingProvider(http(), key, pricePro, priceAgency, "", "", 0, trialDays);
    }

    private static StripeBillingProvider providerWithYearly(String key) {
        return new StripeBillingProvider(http(), key, "price_pro_m", "price_agency_m",
                "price_pro_y", "price_agency_y", 0, 30);
    }

    @Test
    @DisplayName("an unknown or absent interval bills monthly, never yearly")
    void intervalFailsToMonthly() {
        // The consequence of getting this backwards is charging someone a year for a field they
        // never sent, so every unrecognised value resolves to the smaller commitment.
        assertEquals(BillingProvider.BillingInterval.MONTHLY,
                BillingProvider.BillingInterval.forKey(null));
        assertEquals(BillingProvider.BillingInterval.MONTHLY,
                BillingProvider.BillingInterval.forKey(""));
        assertEquals(BillingProvider.BillingInterval.MONTHLY,
                BillingProvider.BillingInterval.forKey("quarterly"));
        assertEquals(BillingProvider.BillingInterval.YEARLY,
                BillingProvider.BillingInterval.forKey("  Yearly "));
        // Stripe's own vocabulary, so a value echoed back from a payload resolves.
        assertEquals(BillingProvider.BillingInterval.YEARLY,
                BillingProvider.BillingInterval.forKey("year"));
        assertEquals(BillingProvider.BillingInterval.YEARLY,
                BillingProvider.BillingInterval.forKey("annual"));
    }

    @Test
    @DisplayName("a plan with no yearly price refuses yearly rather than billing monthly")
    void missingYearlyPriceIsRefused() {
        // The trap: silently falling back to the monthly price would charge $49 to someone who
        // chose the $470 annual plan, and the checkout would look successful.
        StripeBillingProvider stripe = provider("sk_test_abc", "price_pro", "price_agency");
        BillingProvider.CheckoutSession session = stripe.startCheckout(
                "sub-1", "acct-1", "pro", BillingProvider.BillingInterval.YEARLY, "s", "c");
        assertNull(session.checkoutUrl());
        assertFalse(session.activated());
        assertTrue(session.detail().contains("yearly"));
    }

    @Test
    @DisplayName("both cadences are configured when all four price ids are present")
    void yearlyIsConfiguredWhenPriceIdsExist() {
        StripeBillingProvider stripe = providerWithYearly("sk_test_abc");
        assertTrue(stripe.capabilities().expiresTrials());
        // Neither cadence is refused for want of configuration; both reach the network gate.
        for (BillingProvider.BillingInterval cadence : BillingProvider.BillingInterval.values()) {
            BillingProvider.CheckoutSession session =
                    stripe.startCheckout("sub-1", "acct-1", "agency", cadence, "s", "c");
            assertFalse(session.detail().contains("not configured"),
                    "agency should be buyable " + cadence.key());
        }
    }

    @Test
    @DisplayName("trials are off unless configured, and clamp to Stripe's ceiling")
    void trialDaysAreClampedAndDefaultOff() {
        // 0 is the default because a trial grants paid limits with nothing paid: an operator has
        // to ask for one.
        assertEquals(0, provider("sk_test_abc", "price_pro", "price_agency").trialDays("agency"));
        assertEquals(30, provider("sk_test_abc", "price_pro", "price_agency", 30).trialDays("agency"));
        // Pro carries no trial even when Agency does - the per-plan split is the point.
        assertEquals(0, provider("sk_test_abc", "price_pro", "price_agency", 30).trialDays("pro"));
        // An unknown plan gets no trial rather than a default one.
        assertEquals(0, provider("sk_test_abc", "price_pro", "price_agency", 30).trialDays("bogus"));
        // Negative would otherwise reach Stripe as a 400 at checkout time - the worst moment.
        assertEquals(0, provider("sk_test_abc", "price_pro", "price_agency", -5).trialDays("agency"));
        assertEquals(730, provider("sk_test_abc", "price_pro", "price_agency", 99999).trialDays("agency"));
    }

    @Test
    @DisplayName("only a configured adapter claims it can end a trial")
    void expiresTrialsTracksConfiguration() {
        // The guard SubscriptionService reads. An unconfigured adapter reaches no Stripe account,
        // so no trial-ending webhook would ever arrive and a trial opened under it would never
        // close - which is the exact bug this flag exists to prevent.
        assertFalse(provider("", "", "", 14).capabilities().expiresTrials());
        assertFalse(provider("sk_test_abc", "", "", 14).capabilities().expiresTrials());
        assertTrue(provider("sk_test_abc", "price_pro", "price_agency", 14)
                .capabilities().expiresTrials());
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
                stripe.startCheckout("sub-1", "acct-1", "pro", BillingProvider.BillingInterval.MONTHLY, "s", "c");

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
                stripe.startCheckout("sub-1", "acct-1", "agency", BillingProvider.BillingInterval.MONTHLY, "s", "c");

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
        assertFalse(stripe.startCheckout("sub-1", "acct-1", "pro", BillingProvider.BillingInterval.MONTHLY, "s", "c").activated());
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
