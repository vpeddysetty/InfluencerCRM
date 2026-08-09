package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the "is a warning due?" decision, which is the part of M5.6 that can actually be wrong.
 *
 * <p>Phase E built the hosting window and the endpoint to extend it, but nothing warned anyone it
 * was running out — a published page just stopped serving one day. The scheduler closes that, and
 * everything about whether it works correctly lives in {@code thresholdDue}: the sweep, the email
 * and the DAO calls around it are plumbing.
 *
 * <p>These tests deliberately do not start a scheduler or a mail provider. What is worth guarding
 * is the arithmetic and the idempotency, both of which are pure.
 */
class HostingExpirySchedulerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    /** A page expiring in {@code hours}, already warned at {@code warnedAt} (null = never). */
    private static ObjectNode page(long hours, Integer warnedAt) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", "11111111-1111-1111-1111-111111111111");
        node.put("hostingExpiresAt", NOW.plus(Duration.ofHours(hours)).toString());
        if (warnedAt == null) {
            node.putNull("hostingWarningSentAtDays");
        } else {
            node.put("hostingWarningSentAtDays", warnedAt);
        }
        return node;
    }

    @Test
    @DisplayName("a page far from expiry is not warned")
    void noWarningWellBeforeExpiry() {
        // 45 days out is inside the two-month window but past no threshold. Warning here would
        // train people to ignore the mail that matters.
        assertNull(HostingExpiryScheduler.thresholdDue(page(45 * 24, null), NOW));
    }

    @Test
    @DisplayName("each threshold fires as it is crossed")
    void firesAtEachThreshold() {
        assertEquals(30, HostingExpiryScheduler.thresholdDue(page(29 * 24, null), NOW));
        assertEquals(7, HostingExpiryScheduler.thresholdDue(page(7 * 24, 30), NOW));
        assertEquals(1, HostingExpiryScheduler.thresholdDue(page(20, 7), NOW));
    }

    @Test
    @DisplayName("a warning already sent is not repeated")
    void doesNotRepeatAWarning() {
        // The reason the marker column exists. Without it a daily sweep sends the same mail every
        // day for a month, and the 1-day warning arrives in a thread nobody opens any more.
        assertNull(HostingExpiryScheduler.thresholdDue(page(20 * 24, 30), NOW));
        assertNull(HostingExpiryScheduler.thresholdDue(page(5 * 24, 7), NOW));
        assertNull(HostingExpiryScheduler.thresholdDue(page(6, 1), NOW));
    }

    @Test
    @DisplayName("a missed run still sends the warning it skipped")
    void selfHealsAfterAMissedRun() {
        // The failure an exact day-count match hides. If the job was down through day 7 and comes
        // back at day 4, "is it exactly 7?" is now permanently false and that warning is lost. The
        // question asked here is "what is the smallest threshold passed and unsent?", so it still
        // goes out — late, but sent.
        assertEquals(7, HostingExpiryScheduler.thresholdDue(page(4 * 24, 30), NOW));

        // And a page never warned at all, first seen at 3 days, gets the 7-day mail rather than
        // being skipped straight past two thresholds in silence.
        assertEquals(7, HostingExpiryScheduler.thresholdDue(page(3 * 24, null), NOW));
    }

    @Test
    @DisplayName("the final day is warned rather than rounded away")
    void finalDayIsNotLostToRounding() {
        // Days remaining is a ceiling, not a floor. Flooring puts everything inside the last 24
        // hours at 0 days — below every threshold — so the 1-day warning, the one people actually
        // act on, would never be sent at all.
        assertEquals(1, HostingExpiryScheduler.thresholdDue(page(6, 7), NOW));
        assertEquals(1, HostingExpiryScheduler.thresholdDue(page(1, 7), NOW));
    }

    @Test
    @DisplayName("an unstarted hosting window is not an expiring one")
    void nullExpiryIsNotExpiring() {
        // NULL means the page was never published, so the clock has not started. Same distinction
        // BrandDomainService#isExpired makes: not started is not the same as run out.
        ObjectNode unpublished = MAPPER.createObjectNode();
        unpublished.put("id", "x");
        assertNull(HostingExpiryScheduler.thresholdDue(unpublished, NOW));

        ObjectNode explicitNull = MAPPER.createObjectNode();
        explicitNull.putNull("hostingExpiresAt");
        assertNull(HostingExpiryScheduler.thresholdDue(explicitNull, NOW));

        assertNull(HostingExpiryScheduler.thresholdDue(null, NOW));
    }

    @Test
    @DisplayName("an already-expired page is not warned after the fact")
    void expiredPagesAreNotWarned() {
        // The page is already down and the brand has seen that. "1 day left" arriving afterwards
        // is worse than silence — it reads as a system that does not know its own state.
        assertNull(HostingExpiryScheduler.thresholdDue(page(-1, null), NOW));
        assertNull(HostingExpiryScheduler.thresholdDue(page(-30 * 24, 7), NOW));
        assertNull(HostingExpiryScheduler.thresholdDue(page(0, null), NOW));
    }

    @Test
    @DisplayName("an unparseable expiry warns nobody rather than throwing")
    void unparseableExpiryIsIgnored() {
        // Mirrors isExpired: a date we cannot read must not take a page down, and by the same
        // reasoning must not generate a warning about a deadline we do not know.
        ObjectNode broken = MAPPER.createObjectNode();
        broken.put("hostingExpiresAt", "not-a-date");
        assertNull(HostingExpiryScheduler.thresholdDue(broken, NOW));
    }

    @Test
    @DisplayName("extending hosting re-arms the warnings")
    void extensionReArmsWarnings() {
        // BrandDomainService#extendHosting nulls the marker. Without that reset a page extended at
        // day 1 keeps `1` forever and every future warning is suppressed — it would go dark
        // unannounced at the end of the extension, which is the exact defect M5.6 exists to fix.
        assertNull(HostingExpiryScheduler.thresholdDue(page(20, 1), NOW),
                "still silent while the old marker stands");
        assertEquals(1, HostingExpiryScheduler.thresholdDue(page(20, null), NOW),
                "and warns again once extendHosting clears it");
    }

    @Test
    @DisplayName("when several thresholds are due at once, the most urgent one is sent")
    void mostUrgentThresholdWins() {
        // A page first seen with hours left — published during an outage, or restored from a
        // backup — has passed all three thresholds and been warned at none. It gets ONE mail, and
        // it must be the 1-day one: sending "30 days left" to someone whose page dies tomorrow is
        // worse than sending nothing, because it is actively wrong.
        //
        // This is the bug the first implementation had. Scanning descending and returning the
        // first passed threshold answered 30 here, and answered "nothing due" for a page at day 7
        // already warned at day 30 — under-warning exactly where the deadline is closest.
        assertEquals(1, HostingExpiryScheduler.thresholdDue(page(20, null), NOW));
        assertEquals(7, HostingExpiryScheduler.thresholdDue(page(5 * 24, null), NOW));
    }

    @Test
    @DisplayName("thresholds are descending, which the sweep relies on")
    void thresholdsAreDescending() {
        // thresholdDue takes the first threshold a page has passed and treats "already warned at
        // something smaller" as done. Both depend on this order.
        assertEquals(java.util.List.of(30, 7, 1), HostingExpiryScheduler.THRESHOLDS);
    }
}
