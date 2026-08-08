package com.influencer.webe.payout.provider;

import com.influencer.webe.payout.PayoutProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the payout reference against colliding between two payouts to the same creator.
 *
 * <p>The implementation this replaces built {@code "manual-" + creatorId.substring(0, 8)}. A
 * creator is paid repeatedly, so every payout to them carried the identical reference. That is a
 * money-handling defect rather than a cosmetic one: the reference is what an operator matches
 * against a bank statement, and two payments that cannot be told apart cannot be reconciled.
 *
 * <p>The fix derives the reference from the payout id — already unique, already persisted before
 * the provider is called — which also makes the operation idempotent, since recording the same
 * payout twice yields the same reference rather than a second indistinguishable one.
 */
class ManualPayoutProviderTest {

    private final ManualPayoutProvider provider = new ManualPayoutProvider();

    private static final BigDecimal AMOUNT = new BigDecimal("125.00");

    @Test
    @DisplayName("two payouts to the same creator get different references")
    void referencesAreUniquePerPayout() {
        // The exact defect: same creator, two separate payouts. Under the old implementation both
        // of these returned the same string.
        String creatorId = UUID.randomUUID().toString();

        PayoutProvider.PayoutResult first =
                provider.pay(UUID.randomUUID().toString(), creatorId, AMOUNT, "USD", "January");
        PayoutProvider.PayoutResult second =
                provider.pay(UUID.randomUUID().toString(), creatorId, AMOUNT, "USD", "February");

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertNotEquals(first.getProviderRef(), second.getProviderRef(),
                "two payouts to one creator must be individually traceable");
    }

    @Test
    @DisplayName("re-recording the same payout yields the same reference")
    void samePayoutIsIdempotent() {
        // Why the payout id is the key rather than a fresh random: a retry after a timeout must
        // settle once. Same input, same reference — nothing downstream sees a second payment.
        String payoutId = UUID.randomUUID().toString();
        String creatorId = UUID.randomUUID().toString();

        String first = provider.pay(payoutId, creatorId, AMOUNT, "USD", null).getProviderRef();
        String second = provider.pay(payoutId, creatorId, AMOUNT, "USD", null).getProviderRef();

        assertEquals(first, second);
    }

    @Test
    @DisplayName("the reference is traceable back to its payout")
    void referenceCarriesThePayoutId() {
        // The reference exists to be looked up. Embedding the id whole keeps a bank-statement
        // string resolvable to exactly one row, which a truncated id did not.
        String payoutId = UUID.randomUUID().toString();

        assertEquals("manual-" + payoutId,
                provider.pay(payoutId, UUID.randomUUID().toString(), AMOUNT, "USD", null)
                        .getProviderRef());
    }

    @Test
    @DisplayName("a missing payout id fails rather than inventing a reference")
    void missingPayoutIdFails() {
        // Reported as a failure, not thrown: the caller records the status against the row. The
        // alternative — falling back to something creator-derived — would quietly restore the
        // collision this class exists to prevent.
        PayoutProvider.PayoutResult result =
                provider.pay(null, UUID.randomUUID().toString(), AMOUNT, "USD", null);

        assertFalse(result.isSuccess());
        assertEquals("failed", result.getStatus());
        assertFalse(provider.pay("  ", UUID.randomUUID().toString(), AMOUNT, "USD", null)
                .isSuccess());
    }

    @Test
    @DisplayName("the provider declares itself as manual")
    void declaresItself() {
        // Same reasoning as the registrar's provider() and metrics_source: a record of how money
        // moved must not imply an external transfer that never happened.
        assertEquals("manual", provider.key());
    }
}
