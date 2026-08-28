package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep that publishes pages when their time arrives (roadmap PR-35, screen 6).
 *
 * <p>The property under test is <b>late but never early</b>. A page that goes live a few minutes
 * after its scheduled minute is a small annoyance; one that goes live before its embargo is a
 * broken promise to whoever set it. Everything below is a variation on that, plus the two
 * operational rules a sweep must obey: one bad row must not strand the rest, and a retry must not
 * publish twice.
 */
class ScheduledPublishTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records the transitions a sweep asked for, instead of performing them. */
    private static class RecordingStages extends LandingStageService {

        final List<String> published = new ArrayList<>();
        final List<UUID> cleared = new ArrayList<>();
        UUID failOn;

        RecordingStages() {
            super(null, null, null, null, null, null);
        }

        /**
         * Intercepts at {@code publishNow}, which is what the sweep calls.
         *
         * <p>It used to override {@code changeStage}. That stopped recording anything the moment
         * the scheduler moved to {@code publishNow} — the sweep still "worked" against the real
         * method and the assertions saw nothing, which is exactly the kind of silent test drift
         * worth pinning at the seam the caller actually uses.
         */
        @Override
        public JsonNode publishNow(UUID brandId, UUID templateId, String source, String key) {
            if (templateId.equals(failOn)) {
                throw new IllegalStateException("transition refused");
            }
            published.add(templateId + "|published|" + source + "|" + key);
            return MAPPER.createObjectNode();
        }

        @Override
        public JsonNode clearSchedule(UUID brandId, UUID templateId) {
            cleared.add(templateId);
            return MAPPER.createObjectNode();
        }
    }

    /** Returns a fixed page list. */
    private static class StubDao extends DaoGatewayClient {

        private final JsonNode pages;

        StubDao(JsonNode pages) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
            this.pages = pages;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            return pages;
        }
    }

    private ObjectNode page(UUID id, Instant scheduledAt) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id.toString());
        node.put("brandId", UUID.randomUUID().toString());
        if (scheduledAt == null) {
            node.putNull("scheduledPublishAt");
        } else {
            node.put("scheduledPublishAt", scheduledAt.toString());
        }
        return node;
    }

    private ScheduledPublishScheduler schedulerFor(RecordingStages stages, ObjectNode... pages) {
        ArrayNode list = MAPPER.createArrayNode();
        for (ObjectNode page : pages) {
            list.add(page);
        }
        return new ScheduledPublishScheduler(new StubDao(list), stages);
    }

    @Test
    @DisplayName("a page whose time has passed is published; a future one is left alone")
    void publishesOnlyWhatIsDue() {
        UUID due = UUID.randomUUID();
        UUID future = UUID.randomUUID();
        RecordingStages stages = new RecordingStages();

        schedulerFor(stages,
                page(due, Instant.now().minus(Duration.ofMinutes(5))),
                page(future, Instant.now().plus(Duration.ofHours(2))))
                .publishDuePages();

        assertEquals(1, stages.published.size(), "only the due page publishes");
        assertTrue(stages.published.get(0).startsWith(due.toString()));
        assertTrue(stages.published.get(0).contains("|published|"));
    }

    @Test
    @DisplayName("a missed run publishes late rather than skipping")
    void aLongOverduePageStillPublishes() {
        // The sweep asks "which pages are due", not "which are due this minute". A deploy, an
        // outage or a restart must delay a publish, never cancel it.
        UUID overdue = UUID.randomUUID();
        RecordingStages stages = new RecordingStages();

        schedulerFor(stages, page(overdue, Instant.now().minus(Duration.ofDays(3))))
                .publishDuePages();

        assertEquals(1, stages.published.size());
    }

    @Test
    @DisplayName("an unscheduled page is never touched")
    void unscheduledPagesAreIgnored() {
        RecordingStages stages = new RecordingStages();

        schedulerFor(stages, page(UUID.randomUUID(), null)).publishDuePages();

        assertTrue(stages.published.isEmpty());
        assertTrue(stages.cleared.isEmpty(), "nothing to clear on a page that was never scheduled");
    }

    @Test
    @DisplayName("the schedule is cleared after publishing, so a second sweep does nothing")
    void clearingTheScheduleMakesTheJobRunOnce() {
        UUID due = UUID.randomUUID();
        RecordingStages stages = new RecordingStages();

        schedulerFor(stages, page(due, Instant.now().minus(Duration.ofMinutes(1)))).publishDuePages();

        assertEquals(List.of(due), stages.cleared,
                "the pending time is what makes a page due; publishing must consume it");
    }

    @Test
    @DisplayName("the schedule is NOT cleared when the publish fails, so the next run retries")
    void aFailedPublishKeepsItsSchedule() {
        // Clearing first would turn a retryable transition failure into a page that never
        // publishes and no longer remembers that it should have.
        UUID due = UUID.randomUUID();
        RecordingStages stages = new RecordingStages();
        stages.failOn = due;

        schedulerFor(stages, page(due, Instant.now().minus(Duration.ofMinutes(1)))).publishDuePages();

        assertTrue(stages.cleared.isEmpty(), "a page that did not publish must stay scheduled");
    }

    @Test
    @DisplayName("one bad page does not strand the rest of the sweep")
    void oneFailureDoesNotAbortTheBatch() {
        // A batch job that stops on the first bad row silently strands every page behind it, and
        // the failure is invisible until someone asks why their page never went live.
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        RecordingStages stages = new RecordingStages();
        stages.failOn = bad;

        schedulerFor(stages,
                page(bad, Instant.now().minus(Duration.ofMinutes(2))),
                page(good, Instant.now().minus(Duration.ofMinutes(1))))
                .publishDuePages();

        assertEquals(1, stages.published.size());
        assertTrue(stages.published.get(0).startsWith(good.toString()));
    }

    @Test
    @DisplayName("an unreadable timestamp is skipped rather than retried forever")
    void unparseableScheduleIsSkipped() {
        // Left in place it would be re-examined every minute for the life of the page, and each
        // sweep would log the same failure.
        ObjectNode broken = page(UUID.randomUUID(), null);
        broken.put("scheduledPublishAt", "not-a-timestamp");
        RecordingStages stages = new RecordingStages();

        schedulerFor(stages, broken).publishDuePages();

        assertTrue(stages.published.isEmpty());
    }

    @Test
    @DisplayName("the transition is attributed to the scheduler, with a time-derived idempotency key")
    void transitionIsAttributedAndIdempotent() {
        // The audit trail must distinguish "a person pressed publish" from "the clock fired", and
        // the key must be derived from the scheduled instant so a retry is recognised as the same
        // command rather than written as a second transition.
        UUID due = UUID.randomUUID();
        Instant at = Instant.now().minus(Duration.ofMinutes(1));
        RecordingStages stages = new RecordingStages();

        schedulerFor(stages, page(due, at)).publishDuePages();

        String recorded = stages.published.get(0);
        assertTrue(recorded.contains("|" + ScheduledPublishScheduler.SOURCE + "|"),
                "a scheduled publish must not look like a human one in the audit trail");
        assertTrue(recorded.endsWith(due + ":scheduled:" + at),
                "the key is derived from the scheduled instant, not from the run");
    }
}
