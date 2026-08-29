package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
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
 * The sweep that notices a handoff has gone quiet (roadmap PR-44).
 *
 * <p>Ghosting is the modal outcome in creator marketing, so this is not an edge case — it is the
 * commonest ending. The two properties worth guarding hardest are that a reminder is sent
 * <b>once</b> per turn, and that a page which has waited long enough escalates to the brand rather
 * than nagging the creator it has already outgrown.
 */
class HandoffReminderSweepTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    /** Records what the sweep asked for and what it wrote back. */
    private static class RecordingDao extends DaoGatewayClient {

        final List<ObjectNode> puts = new ArrayList<>();
        JsonNode awaiting = MAPPER.createArrayNode();

        RecordingDao() {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            return path.contains("awaiting-turn") ? awaiting : MAPPER.createObjectNode();
        }

        @Override
        public JsonNode put(String path, JsonNode body) {
            puts.add((ObjectNode) body);
            return body;
        }
    }

    /** Counts which nudge fired, without sending anything. */
    private static class CountingNotifier extends CollaboratorNotifier {

        final List<String> nudges = new ArrayList<>();
        boolean deliver = true;

        CountingNotifier() {
            super(null, null, null, "", "", "");
        }

        @Override
        public boolean remindCreator(UUID brandId, UUID templateId) {
            nudges.add("creator");
            return deliver;
        }

        @Override
        public boolean notifyStalledToBrand(UUID brandId, UUID templateId, long daysWaiting) {
            nudges.add("brand:" + daysWaiting);
            return deliver;
        }
    }

    @Test
    @DisplayName("a page waiting four days reminds the creator")
    void remindsTheCreatorFirst() {
        RecordingDao dao = new RecordingDao();
        dao.awaiting = pages(page(Duration.ofDays(4), null));
        CountingNotifier notifier = new CountingNotifier();

        sweeper(dao, notifier).sweep();

        assertEquals(List.of("creator"), notifier.nudges);
    }

    @Test
    @DisplayName("a page waiting eight days escalates to the brand instead")
    void escalatesRatherThanNagging() {
        // Longest threshold checked FIRST. Otherwise a page that has waited eight days sends the
        // creator a three-day reminder it has already outgrown, and the brand -- the only party
        // who can actually act by now -- is never told.
        RecordingDao dao = new RecordingDao();
        dao.awaiting = pages(page(Duration.ofDays(8), null));
        CountingNotifier notifier = new CountingNotifier();

        sweeper(dao, notifier).sweep();

        assertEquals(1, notifier.nudges.size());
        assertTrue(notifier.nudges.get(0).startsWith("brand:"), "the brand is told, not the creator");
    }

    @Test
    @DisplayName("a reminder already sent since the turn moved is not sent again")
    void doesNotRepeatItself() {
        // THE property. Without the stamp an hourly sweep sees "four days elapsed" at hour 96, 97
        // and 98 and emails every hour until the creator acts -- worse than no reminder, and how a
        // sending domain gets marked as spam.
        RecordingDao dao = new RecordingDao();
        dao.awaiting = pages(page(Duration.ofDays(4), Duration.ofDays(1)));
        CountingNotifier notifier = new CountingNotifier();

        sweeper(dao, notifier).sweep();

        assertTrue(notifier.nudges.isEmpty(), "one reminder per turn, not one per sweep");
    }

    @Test
    @DisplayName("a reminder sent BEFORE the turn moved does not suppress a new one")
    void handingBackAndForthReArmsTheSweep() {
        // The stamp is compared against turnChangedAt rather than cleared on handoff. A page
        // reminded once, handed back, and handed out again is a fresh wait -- and a flag would
        // have had to be found and reset for that to work.
        RecordingDao dao = new RecordingDao();
        ObjectNode page = page(Duration.ofDays(4), null);
        // Reminded five days ago; the turn moved four days ago. The stamp is older, so it is stale.
        page.put("handoffReminderSentAt", Instant.now().minus(Duration.ofDays(5)).toString());
        dao.awaiting = pages(page);
        CountingNotifier notifier = new CountingNotifier();

        sweeper(dao, notifier).sweep();

        assertEquals(List.of("creator"), notifier.nudges);
    }

    @Test
    @DisplayName("a page waiting on the brand is left alone")
    void ignoresPagesWaitingOnTheBrand() {
        // Their own backlog. Emailing somebody about their own to-do list is noise, and noise is
        // what makes the useful notifications get filtered.
        RecordingDao dao = new RecordingDao();
        ObjectNode page = page(Duration.ofDays(9), null);
        page.put("turn", HandoffMachine.BRAND);
        dao.awaiting = pages(page);
        CountingNotifier notifier = new CountingNotifier();

        sweeper(dao, notifier).sweep();

        assertTrue(notifier.nudges.isEmpty());
    }

    @Test
    @DisplayName("a reminder that could not be sent is not stamped")
    void undeliveredRemindersAreRetried() {
        // Stamping a failed send would silence the page permanently on one transient mail failure
        // -- the exact outcome this sweep exists to prevent.
        RecordingDao dao = new RecordingDao();
        dao.awaiting = pages(page(Duration.ofDays(4), null));
        CountingNotifier notifier = new CountingNotifier();
        notifier.deliver = false;

        sweeper(dao, notifier).sweep();

        assertEquals(List.of("creator"), notifier.nudges, "it tried");
        assertTrue(dao.puts.isEmpty(), "but nothing was recorded, so the next sweep tries again");
    }

    @Test
    @DisplayName("stamping the reminder does not drop the page out of the sweep")
    void stampPreservesTheTurn() {
        // The DAO's PUT replaces the row and null-guards `turn`, so a write that omitted it would
        // leave the turn intact by luck rather than intent -- but omitting turnChangedAt would
        // reset the clock, and omitting scheduledPublishAt would cancel a launch. Restated for the
        // same reason every partial write to this row restates them.
        RecordingDao dao = new RecordingDao();
        dao.awaiting = pages(page(Duration.ofDays(4), null));

        sweeper(dao, new CountingNotifier()).sweep();

        assertEquals(1, dao.puts.size());
        ObjectNode written = dao.puts.get(0);
        assertEquals(HandoffMachine.CREATOR, written.get("turn").asText());
        assertTrue(written.hasNonNull("turnChangedAt"), "the clock must not restart");
        assertTrue(written.hasNonNull("handoffReminderSentAt"));
    }

    // ---- fixtures ------------------------------------------------------

    private HandoffReminderScheduler sweeper(RecordingDao dao, CountingNotifier notifier) {
        return new HandoffReminderScheduler(dao, new ResponseShapeService(MAPPER), notifier, true);
    }

    private ArrayNode pages(ObjectNode... rows) {
        ArrayNode out = MAPPER.createArrayNode();
        for (ObjectNode row : rows) {
            out.add(row);
        }
        return out;
    }

    private ObjectNode page(Duration waiting, Duration remindedAgo) {
        ObjectNode page = MAPPER.createObjectNode();
        page.put("id", UUID.randomUUID().toString());
        page.put("brandId", BRAND.toString());
        page.put("campaignId", UUID.randomUUID().toString());
        page.put("publicSlug", "winter-trail");
        page.put("name", "Winter trail");
        page.put("status", "draft");
        page.put("stage", LandingStageMachine.CONTENT_NEEDED);
        page.put("turn", HandoffMachine.CREATOR);
        page.put("turnChangedAt", Instant.now().minus(waiting).toString());
        if (remindedAgo != null) {
            page.put("handoffReminderSentAt", Instant.now().minus(remindedAgo).toString());
        }
        return page;
    }
}
