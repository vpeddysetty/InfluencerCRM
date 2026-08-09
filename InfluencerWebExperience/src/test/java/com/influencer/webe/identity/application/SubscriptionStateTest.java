package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the subscription lifecycle.
 *
 * <p>Every way this can go wrong is silent. A state that can be entered but not left strands a
 * paying customer; a transition that skips a guard grants paid limits to someone who is not
 * paying; and an effective plan that disagrees with the billed plan makes entitlements drift from
 * billing with nothing reporting it.
 */
class SubscriptionStateTest {

    @Test
    @DisplayName("cancelled is terminal — nothing comes back from it")
    void cancelledIsTerminal() {
        // At-least-once delivery makes out-of-order events real: a late `subscription.updated`
        // carrying "active" must not resurrect a cancelled subscription and start granting paid
        // limits again to an account that stopped paying.
        assertTrue(SubscriptionState.isTerminal(SubscriptionState.CANCELLED));
        assertFalse(SubscriptionState.canTransition(SubscriptionState.CANCELLED, SubscriptionState.ACTIVE));
        assertFalse(SubscriptionState.canTransition(SubscriptionState.CANCELLED, SubscriptionState.PAUSED));
        assertFalse(SubscriptionState.canTransition(SubscriptionState.CANCELLED, SubscriptionState.TRIALING));
    }

    @Test
    @DisplayName("a replayed event is a no-op, not an error")
    void sameStateIsAllowed() {
        // Providers deliver at-least-once, so a second `updated` carrying the status we already
        // hold is expected traffic. Refusing it would turn normal delivery into a logged failure.
        for (String status : SubscriptionState.ALL) {
            assertTrue(SubscriptionState.canTransition(status, status), status + " -> " + status);
        }
    }

    @Test
    @DisplayName("nothing returns to trialing")
    void trialIsOfferedOnce() {
        // Otherwise a free extension is available to anyone who can trigger the right event.
        assertFalse(SubscriptionState.canTransition(SubscriptionState.ACTIVE, SubscriptionState.TRIALING));
        assertFalse(SubscriptionState.canTransition(SubscriptionState.PAUSED, SubscriptionState.TRIALING));
        assertFalse(SubscriptionState.canTransition(SubscriptionState.PAST_DUE, SubscriptionState.TRIALING));
    }

    @Test
    @DisplayName("pause and resume round-trip")
    void pauseResumeRoundTrip() {
        assertTrue(SubscriptionState.canPause(SubscriptionState.ACTIVE));
        assertTrue(SubscriptionState.canTransition(SubscriptionState.ACTIVE, SubscriptionState.PAUSED));
        assertTrue(SubscriptionState.canResume(SubscriptionState.PAUSED));
        assertTrue(SubscriptionState.canTransition(SubscriptionState.PAUSED, SubscriptionState.ACTIVE));
    }

    @Test
    @DisplayName("a past-due subscription cannot be paused")
    void pastDueCannotBePaused() {
        // Pausing would look like a way to stop the retries, and it is not — the charge is still
        // owed. Allowing it would let anyone dodge a failed payment by pausing.
        assertFalse(SubscriptionState.canPause(SubscriptionState.PAST_DUE));
        assertFalse(SubscriptionState.canTransition(SubscriptionState.PAST_DUE, SubscriptionState.PAUSED));
        // But it can always be cancelled — nobody should be trapped in past_due.
        assertTrue(SubscriptionState.canCancel(SubscriptionState.PAST_DUE));
    }

    @Test
    @DisplayName("a paused subscription cannot be paused again")
    void pauseIsNotIdempotentAsAnAction() {
        assertFalse(SubscriptionState.canPause(SubscriptionState.PAUSED));
        assertFalse(SubscriptionState.canResume(SubscriptionState.ACTIVE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"trialing", "active", "paused", "past_due"})
    @DisplayName("everything unfinished can be cancelled")
    void everythingLiveCanBeCancelled(String status) {
        // The product feature: the competitor's most-cited complaint is cancellation being
        // "impossible to stop". No live state may be a trap.
        assertTrue(SubscriptionState.canCancel(status));
        assertTrue(SubscriptionState.canTransition(status, SubscriptionState.CANCELLED));
    }

    @Test
    @DisplayName("a failed charge does not immediately downgrade the account")
    void pastDueKeepsThePaidPlan() {
        // Usually an expired card, and the provider retries for days. Breaking someone's workspace
        // the moment a renewal fails punishes them for a problem they may not know about and are
        // about to fix.
        assertTrue(SubscriptionState.grantsPaidPlan(SubscriptionState.PAST_DUE));
        assertEquals("pro", SubscriptionState.effectivePlan("pro", SubscriptionState.PAST_DUE));
    }

    @Test
    @DisplayName("pausing drops the enforced plan while the row keeps the billed one")
    void pausingDropsEntitlementsButNotThePlan() {
        // The reason subscriptions.plan and accounts.plan are separate columns. The subscription
        // still says "pro" — that is what resumes — while what gets enforced is free.
        assertFalse(SubscriptionState.grantsPaidPlan(SubscriptionState.PAUSED));
        assertEquals("free", SubscriptionState.effectivePlan("pro", SubscriptionState.PAUSED));
        assertEquals("free", SubscriptionState.effectivePlan("agency", SubscriptionState.CANCELLED));
    }

    @Test
    @DisplayName("an unrecognised plan resolves to free, never to unlimited")
    void effectivePlanFailsClosed() {
        // Delegates to PlanPolicy, which fails closed. A typo in a plan string must not become a
        // free upgrade to unlimited.
        assertEquals("free", SubscriptionState.effectivePlan("enterprise", SubscriptionState.ACTIVE));
        assertEquals("free", SubscriptionState.effectivePlan(null, SubscriptionState.ACTIVE));
    }

    @Test
    @DisplayName("an unknown status grants nothing and moves nowhere")
    void unknownStatusFailsClosed() {
        assertFalse(SubscriptionState.isKnown("halfway"));
        assertFalse(SubscriptionState.grantsPaidPlan("halfway"));
        assertEquals("free", SubscriptionState.effectivePlan("pro", "halfway"));
        assertFalse(SubscriptionState.canTransition("halfway", SubscriptionState.ACTIVE));
        assertFalse(SubscriptionState.canTransition(SubscriptionState.ACTIVE, "halfway"));
    }

    @Test
    @DisplayName("status handling is case- and whitespace-insensitive")
    void statusNormalizes() {
        // The column is free text behind a CHECK constraint, and payloads come from outside.
        assertTrue(SubscriptionState.grantsPaidPlan(" ACTIVE "));
        assertTrue(SubscriptionState.canPause("Active"));
        assertEquals("Paused", SubscriptionState.label("PAUSED"));
    }

    @Test
    @DisplayName("every state has a human label")
    void everyStateIsPresentable() {
        // A UI should never render a raw enum, and "past_due" in particular reads as a bug.
        for (String status : SubscriptionState.ALL) {
            assertFalse(SubscriptionState.label(status).equals("Unknown"), status + " has no label");
            assertFalse(SubscriptionState.label(status).contains("_"), status + " leaks its raw form");
        }
        assertEquals("Payment failed", SubscriptionState.label(SubscriptionState.PAST_DUE));
    }
}
