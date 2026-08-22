package com.influencer.webe.billing;

import com.influencer.webe.billing.provider.ManualBillingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one rule that matters most in this package: an adapter that takes no money must never
 * be mistakable for one that does.
 *
 * <p>The codebase already applies this to simulated follower counts and unsent email. It matters
 * more here, because the simulated thing is somebody's payment. An account recorded as subscribed
 * without a charge has to stay distinguishable from one that actually paid — forever, in the data,
 * not just in a log line someone might read.
 */
class ManualBillingProviderTest {

    private final ManualBillingProvider provider = new ManualBillingProvider();

    @Test
    @DisplayName("it never claims to have charged anyone")
    void takesNoMoneyAndSaysSo() {
        // The flag everything downstream reads before presenting a subscription as paid.
        assertFalse(provider.capabilities().chargesMoney(),
                "a provider that takes no money must report chargesMoney=false");
        assertEquals("manual", provider.key());
    }

    @Test
    @DisplayName("it offers no checkout URL rather than a fake one")
    void noFakeCheckoutUrl() {
        // Returning a plausible-looking URL would be the exact failure this class exists to avoid:
        // sending someone to a page that collects nothing while implying it does.
        BillingProvider.CheckoutSession session = provider.startCheckout(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "pro",
                BillingProvider.BillingInterval.MONTHLY, null, null);

        assertNull(session.checkoutUrl());
        assertFalse(provider.capabilities().hostedCheckout());
        assertTrue(session.detail().toLowerCase().contains("no money"),
                "the detail must say plainly that nothing was taken: " + session.detail());
    }

    @Test
    @DisplayName("activation is immediate, because there is no payment to wait for")
    void activatesImmediately() {
        // The one behaviour a real hosted-checkout provider genuinely differs on: it returns
        // activated=false and waits for its webhook, because the user has not paid yet.
        assertTrue(provider.startCheckout(UUID.randomUUID().toString(), "acct", "pro", BillingProvider.BillingInterval.MONTHLY, null, null)
                .activated());
    }

    @Test
    @DisplayName("the same subscription recorded twice yields the same reference")
    void referenceIsIdempotent() {
        // The subscription row's id is the idempotency key, the same discipline
        // ManualPayoutProvider follows: a retry after a timeout must not produce a second
        // subscription that looks unrelated to the first.
        String subscriptionId = UUID.randomUUID().toString();

        String first = provider.startCheckout(subscriptionId, "acct", "pro", BillingProvider.BillingInterval.MONTHLY, null, null).providerRef();
        String second = provider.startCheckout(subscriptionId, "acct", "pro", BillingProvider.BillingInterval.MONTHLY, null, null).providerRef();

        assertEquals(first, second);
        assertTrue(first.contains(subscriptionId), "the reference must be traceable to its subscription");
    }

    @Test
    @DisplayName("two different subscriptions get different references")
    void referencesAreUnique() {
        String a = provider.startCheckout(UUID.randomUUID().toString(), "acct", "pro", BillingProvider.BillingInterval.MONTHLY, null, null).providerRef();
        String b = provider.startCheckout(UUID.randomUUID().toString(), "acct", "pro", BillingProvider.BillingInterval.MONTHLY, null, null).providerRef();

        assertFalse(a.equals(b));
    }

    @Test
    @DisplayName("pause, resume and cancel all succeed and say nothing was billed")
    void lifecycleIsHonest() {
        for (BillingProvider.Result result : List.of(
                provider.pause("ref"), provider.resume("ref"), provider.cancel("ref"))) {
            assertTrue(result.ok());
            assertEquals("manual", result.provider());
            assertTrue(result.detail().toLowerCase().contains("billed"),
                    "each outcome must state that nothing was being billed: " + result.detail());
        }
    }

    @Test
    @DisplayName("the registry falls back to manual rather than failing open")
    void registryFallsBackToManual() {
        // A typo in WEBE_BILLING_PROVIDER must degrade to the adapter that takes no money and says
        // so — not to whichever bean happens to be first, and not to a dead application.
        BillingProviderRegistry registry =
                new BillingProviderRegistry(List.of(provider), "stripe-typo");

        assertEquals("manual", registry.active().key());
    }

    @Test
    @DisplayName("a blank idempotency key produces no reference at all")
    void blankKeyProducesNoReference() {
        // Better to record nothing than to invent a reference that cannot be traced back.
        assertNull(provider.startCheckout(null, "acct", "pro", BillingProvider.BillingInterval.MONTHLY, null, null).providerRef());
        assertNull(provider.startCheckout("  ", "acct", "pro", BillingProvider.BillingInterval.MONTHLY, null, null).providerRef());
    }
}
