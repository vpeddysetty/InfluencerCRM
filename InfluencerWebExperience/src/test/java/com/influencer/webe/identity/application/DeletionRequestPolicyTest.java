package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide whether an account gets destroyed.
 *
 * <p>Deletion is irreversible, so the cases worth pinning are the ones where getting it wrong
 * destroys data: executing without approval, executing twice, and honouring a link that should have
 * stopped working.
 */
class DeletionRequestPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Nested
    @DisplayName("execution gate")
    class ExecutionGate {

        @Test
        @DisplayName("an unapproved request is never executed")
        void unapprovedNeverRuns() {
            // The rule the whole design serves. An inbound email is a claim; a human approval is
            // the authorisation. V40 enforces the same thing in a CHECK constraint.
            assertFalse(DeletionRequestPolicy.mayExecute(null, null, null));
        }

        @Test
        @DisplayName("an approved request runs once")
        void approvedRuns() {
            assertTrue(DeletionRequestPolicy.mayExecute(NOW, null, null));
        }

        @Test
        @DisplayName("an already-completed request does not run again")
        void completedDoesNotRerun() {
            // At-least-once delivery of the approval, a double-clicked link, or a retry after a
            // timeout must not produce a second irreversible act.
            assertFalse(DeletionRequestPolicy.mayExecute(NOW, NOW, null));
        }

        @Test
        @DisplayName("a refused request does not run even if it was somehow approved")
        void refusedDoesNotRun() {
            assertFalse(DeletionRequestPolicy.mayExecute(NOW, null, NOW));
        }
    }

    @Nested
    @DisplayName("approval links")
    class ApprovalLinks {

        @Test
        @DisplayName("a live link works")
        void liveLinkWorks() {
            assertTrue(DeletionRequestPolicy.approvalUsable(NOW.plusSeconds(3600), null, NOW));
        }

        @Test
        @DisplayName("an expired link does not")
        void expiredLinkFails() {
            assertFalse(DeletionRequestPolicy.approvalUsable(NOW.minusSeconds(1), null, NOW));
        }

        @Test
        @DisplayName("a link is single use")
        void singleUse() {
            // A forwarded approval email must not stay live.
            assertFalse(DeletionRequestPolicy.approvalUsable(NOW.plusSeconds(3600), NOW, NOW));
        }

        @Test
        @DisplayName("a missing expiry is treated as unusable, not as unlimited")
        void missingExpiryIsRefused() {
            // Fails closed. A null expiry means something went wrong when the token was issued,
            // and the safe reading of "no deadline recorded" is "do not honour it".
            assertFalse(DeletionRequestPolicy.approvalUsable(null, null, NOW));
        }
    }

    @Nested
    @DisplayName("reading the message")
    class ReadingTheMessage {

        @Test
        @DisplayName("plain requests are recognised")
        void recognisesPlainRequests() {
            assertTrue(DeletionRequestPolicy.readsAsDeletionRequest(
                    "Delete my account", "Please delete all my data."));
            assertTrue(DeletionRequestPolicy.readsAsDeletionRequest(
                    "GDPR request", "I am exercising my right to erasure of my personal data."));
            assertTrue(DeletionRequestPolicy.readsAsDeletionRequest(
                    null, "please remove my account"));
        }

        @Test
        @DisplayName("unrelated mail is not mistaken for one")
        void ignoresUnrelatedMail() {
            assertFalse(DeletionRequestPolicy.readsAsDeletionRequest(
                    "Invoice #4021", "Attached is your receipt."));
            assertFalse(DeletionRequestPolicy.readsAsDeletionRequest("", ""));
            assertFalse(DeletionRequestPolicy.readsAsDeletionRequest(null, null));
        }

        @Test
        @DisplayName("triage errs toward flagging, because a missed request is the worse failure")
        void errsTowardFlagging() {
            // A false positive costs a glance at a notification the operator was getting anyway.
            // A false negative silently drops a rights request and nobody finds out.
            assertTrue(DeletionRequestPolicy.readsAsDeletionRequest(
                    "question", "can you remove the data you hold about me?"));
        }
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("an ordinary request is account-scoped")
        void defaultsToAccount() {
            assertEquals(DeletionRequestPolicy.SCOPE_ACCOUNT,
                    DeletionRequestPolicy.scopeFor("Delete my account", "please delete everything"));
            assertNull(DeletionRequestPolicy.providerNamedIn("Delete my account", "everything"));
        }

        @Test
        @DisplayName("a Facebook-only request is provider-scoped")
        void detectsProviderScope() {
            // Meta requires this route and their reviewers test it.
            assertEquals("facebook", DeletionRequestPolicy.providerNamedIn(
                    "Disconnect Facebook", "please delete only my Facebook data"));
            assertEquals(DeletionRequestPolicy.SCOPE_PROVIDER, DeletionRequestPolicy.scopeFor(
                    "Disconnect Facebook", "please delete only my Facebook data"));
        }

        @Test
        @DisplayName("naming a provider without limiting to it is still an account request")
        void mentioningAProviderIsNotEnough() {
            // "Delete my account, I signed up with Google" is a full erasure request. Reading it as
            // provider-scoped would leave the account alive and the request unhonoured.
            assertEquals(DeletionRequestPolicy.SCOPE_ACCOUNT, DeletionRequestPolicy.scopeFor(
                    "Delete my account", "I signed up with Google, please delete my data"));
        }
    }

    @Nested
    @DisplayName("addresses")
    class Addresses {

        @Test
        @DisplayName("a display-name header yields the bare address")
        void extractsFromDisplayName() {
            assertEquals("vijay.peddysetty@kmpsglobal.com",
                    DeletionRequestPolicy.addressFrom("\"Vijay Peddysetty\" <Vijay.Peddysetty@kmpsglobal.com>"));
        }

        @Test
        @DisplayName("a bare address passes through, lowercased")
        void handlesBareAddress() {
            // Lowercased because the lookup is against citext; a mixed-case miss would tell someone
            // their account cannot be found when it is right there.
            assertEquals("a@b.com", DeletionRequestPolicy.addressFrom("A@B.com"));
        }

        @Test
        @DisplayName("plus addressing survives")
        void keepsPlusAddressing() {
            assertEquals("a+tag@b.com", DeletionRequestPolicy.addressFrom("<a+tag@b.com>"));
        }

        @Test
        @DisplayName("junk yields null rather than a bad lookup")
        void rejectsJunk() {
            assertNull(DeletionRequestPolicy.addressFrom(null));
            assertNull(DeletionRequestPolicy.addressFrom(""));
            assertNull(DeletionRequestPolicy.addressFrom("Mailer Daemon"));
            assertNull(DeletionRequestPolicy.addressFrom("@nouser.com"));
        }
    }

    @Nested
    @DisplayName("the published deadlines")
    class Deadlines {

        @Test
        @DisplayName("acknowledgement and completion match what /data-deletion/ promises")
        void deadlinesMatchThePublishedPage() {
            Instant requested = Instant.parse("2026-08-01T00:00:00Z");
            assertEquals(Instant.parse("2026-08-06T00:00:00Z"),
                    DeletionRequestPolicy.acknowledgementDue(requested));
            assertEquals(Instant.parse("2026-08-31T00:00:00Z"),
                    DeletionRequestPolicy.completionDue(requested));
        }

        @Test
        @DisplayName("an open request past thirty days is overdue")
        void overdueWhenOpenPastDeadline() {
            Instant requested = Instant.parse("2026-07-01T00:00:00Z");
            assertTrue(DeletionRequestPolicy.isOverdue(requested, null, null, NOW));
        }

        @Test
        @DisplayName("a settled request is never overdue, however old")
        void settledIsNeverOverdue() {
            Instant requested = Instant.parse("2026-01-01T00:00:00Z");
            assertFalse(DeletionRequestPolicy.isOverdue(requested, NOW, null, NOW));
            // A refusal is an outcome, not an unfinished job -- V37 is explicit about this.
            assertFalse(DeletionRequestPolicy.isOverdue(requested, null, NOW, NOW));
        }
    }
}
