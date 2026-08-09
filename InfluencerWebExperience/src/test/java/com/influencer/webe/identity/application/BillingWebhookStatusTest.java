package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for a bug live sandbox testing found and unit tests did not.
 *
 * <p><b>What happened.</b> Pausing wrote {@code status=paused} and dropped the account to free
 * limits, correctly. Then Stripe's {@code customer.subscription.updated} arrived in the same
 * second, the handler read its {@code status} field — {@code active} — and reverted everything. A
 * customer who paused kept paying-tier entitlements, and nothing anywhere reported a problem.
 *
 * <p><b>Why no unit test caught it.</b> Every test used the generic payload shape, where
 * {@code status} means what it says. The assumption that a provider represents a pause <em>as a
 * status</em> was never written down, so it was never checked. Stripe has no paused status at all:
 * it keeps {@code active} and expresses the pause through {@code pause_collection}.
 */
class BillingWebhookStatusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode payload(String status) {
        return MAPPER.createObjectNode().put("status", status);
    }

    @Test
    @DisplayName("a Stripe pause is read as paused, not as the active status it carries")
    void pauseCollectionMeansPaused() {
        // The exact payload that caused the bug: Stripe reports active + pause_collection.
        ObjectNode paused = payload("active");
        paused.putObject("pause_collection").put("behavior", "void");

        assertEquals(SubscriptionState.PAUSED, BillingWebhookService.statusFrom(paused),
                "reading `status` alone reverts the pause the user just made");
    }

    @Test
    @DisplayName("clearing pause_collection resumes rather than pausing again")
    void nullPauseCollectionMeansResumed() {
        // How Stripe expresses a resume: pause_collection present but null, status back to active.
        ObjectNode resumed = payload("active");
        resumed.putNull("pause_collection");

        assertEquals(SubscriptionState.ACTIVE, BillingWebhookService.statusFrom(resumed));
    }

    @Test
    @DisplayName("a cancelled subscription stays cancelled despite a null pause_collection")
    void cancelledIsNotResurrectedByPauseFields() {
        // A cancelled Stripe subscription also carries pause_collection = null. Treating that as
        // "resume" would drag a cancelled subscription back to active and restore paid limits to
        // someone who stopped paying.
        ObjectNode cancelled = payload("canceled");
        cancelled.putNull("pause_collection");

        assertEquals("canceled", BillingWebhookService.statusFrom(cancelled),
                "the status must win when it is terminal");
    }

    @Test
    @DisplayName("a payload without pause fields falls through to status")
    void genericShapeIsUnaffected() {
        // The non-Stripe shape, and every existing test. This must keep working unchanged.
        assertEquals(SubscriptionState.ACTIVE, BillingWebhookService.statusFrom(payload("active")));
        assertEquals(SubscriptionState.PAST_DUE, BillingWebhookService.statusFrom(payload("past_due")));
        assertEquals(SubscriptionState.PAUSED, BillingWebhookService.statusFrom(payload("paused")));
    }

    @Test
    @DisplayName("the resulting transitions are ones the state machine permits")
    void mappedStatusesAreReachable() {
        // The mapping is only useful if the state machine then allows the move. active -> paused
        // and paused -> active must both be legal, or the webhook would be refused as illegal
        // right after being read correctly.
        assertEquals(true, SubscriptionState.canTransition(
                SubscriptionState.ACTIVE, SubscriptionState.PAUSED));
        assertEquals(true, SubscriptionState.canTransition(
                SubscriptionState.PAUSED, SubscriptionState.ACTIVE));
    }
}
