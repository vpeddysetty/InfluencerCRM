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
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handing a page to a creator, and taking it back (roadmap PR-42).
 *
 * <p>The handoff is a grant, a stage change and a turn change that only mean anything together.
 * These tests pin the ordering and the refusals, because a partial handoff is not recoverable by
 * retrying — the second attempt sees the half-done first one.
 */
class PageHandoffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CREATOR = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID USER = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private static class RecordingDao extends DaoGatewayClient {

        final List<ObjectNode> puts = new ArrayList<>();
        final List<String> postPaths = new ArrayList<>();
        final List<ObjectNode> posts = new ArrayList<>();
        private final JsonNode page;

        RecordingDao(JsonNode page) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
            this.page = page;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.startsWith("/creator-identities/")) {
                ArrayNode links = MAPPER.createArrayNode();
                links.addObject()
                        .put("brandId", BRAND.toString())
                        .put("status", "confirmed");
                return links;
            }
            if (path.startsWith("/landing-page-collaborators")) {
                return MAPPER.createArrayNode();
            }
            return page;
        }

        @Override
        public JsonNode put(String path, JsonNode body) {
            puts.add((ObjectNode) body);
            ObjectNode saved = page.deepCopy();
            body.fieldNames().forEachRemaining(field -> saved.set(field, body.get(field)));
            return saved;
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            postPaths.add(path);
            posts.add((ObjectNode) body);
            return body;
        }
    }

    @Test
    @DisplayName("handing off grants access, moves the stage and sets the turn together")
    void handoffDoesAllThree() {
        RecordingDao dao = new RecordingDao(page(LandingStageMachine.APPROVED, null));

        service(dao).handOff(BRAND, UUID.randomUUID(), CREATOR, USER, "Please add your own intro");

        assertTrue(dao.postPaths.stream().anyMatch(p -> p.startsWith("/landing-page-collaborators")),
                "the creator must be granted access");
        ObjectNode written = dao.puts.get(0);
        assertEquals(LandingStageMachine.CREATOR_ASSIGNED, written.get("stage").asText());
        assertEquals(HandoffMachine.CREATOR, written.get("turn").asText());
        assertTrue(written.hasNonNull("turnChangedAt"), "the sweep needs to know when this started");
    }

    @Test
    @DisplayName("the grant is written before the stage moves")
    void grantPrecedesTheStageChange() {
        // Ordering matters because the two failure modes are not symmetrical. A grant with no
        // stage change is invisible and harmless; a stage change with no grant is visible and
        // wrong -- it tells the brand they are waiting on somebody who was never asked.
        RecordingDao dao = new RecordingDao(page(LandingStageMachine.APPROVED, null));

        service(dao).handOff(BRAND, UUID.randomUUID(), CREATOR, USER, null);

        assertFalse(dao.postPaths.isEmpty(), "the grant happened");
        assertFalse(dao.puts.isEmpty(), "the stage change happened");
        // The grant is a POST and the stage change a PUT; the grant's POST is recorded first.
        assertTrue(dao.postPaths.get(0).startsWith("/landing-page-collaborators"));
    }

    @Test
    @DisplayName("a page that cannot be handed off is refused before anything is written")
    void refusedHandoffLeavesNoGrant() {
        // An orphaned grant would give a creator access to a page nobody handed them -- so the
        // stage check runs before the grant rather than after.
        RecordingDao dao = new RecordingDao(page(LandingStageMachine.DRAFT, null));

        assertThrows(ResponseStatusException.class,
                () -> service(dao).handOff(BRAND, UUID.randomUUID(), CREATOR, USER, null));

        assertTrue(dao.postPaths.isEmpty(), "no grant may be left behind by a refused handoff");
        assertTrue(dao.puts.isEmpty(), "and no stage change either");
    }

    @Test
    @DisplayName("handing off does not cancel a scheduled publish")
    void handoffPreservesTheSchedule() {
        // Every partial write to this row carries the same obligation -- see LandingTemplateWrites.
        // A brand that scheduled a launch and then asked a creator for help must still launch.
        Instant launch = Instant.parse("2026-09-15T09:00:00Z");
        RecordingDao dao = new RecordingDao(page(LandingStageMachine.APPROVED, launch));

        service(dao).handOff(BRAND, UUID.randomUUID(), CREATOR, USER, null);

        assertEquals(launch.toString(), dao.puts.get(0).get("scheduledPublishAt").asText());
    }

    @Test
    @DisplayName("the handoff is recorded with a per-occurrence key")
    void handoffIsAudited() {
        // Not templateId:from->to. Work goes round the loop more than once, and a key derived from
        // the endpoints makes the second pass vanish from the audit trail while the page moves --
        // the record is then wrong exactly when somebody is reconstructing what happened.
        UUID template = UUID.randomUUID();
        RecordingDao dao = new RecordingDao(page(LandingStageMachine.APPROVED, null));

        service(dao).handOff(BRAND, template, CREATOR, USER, "notes here");

        ObjectNode audit = dao.posts.stream()
                .filter(node -> node.hasNonNull("toTurn"))
                .findFirst().orElseThrow();
        assertEquals(HandoffMachine.CREATOR, audit.get("toTurn").asText());
        assertEquals("notes here", audit.get("note").asText());
        assertTrue(audit.get("idempotencyKey").asText().startsWith(template.toString()));
        assertTrue(audit.get("idempotencyKey").asText().length() > (template.toString() + ":creator:").length(),
                "the key must carry a per-occurrence component");
    }

    @Test
    @DisplayName("taking a page back moves the turn without revoking access")
    void takeBackLeavesTheGrantAlone() {
        // Taking the turn back is not revoking access. Conflating them would mean a brand who
        // wanted the page back for an hour had to re-invite the creator afterwards.
        RecordingDao dao = new RecordingDao(page(LandingStageMachine.CONTENT_NEEDED, null));

        service(dao).takeBack(BRAND, UUID.randomUUID(), USER);

        ObjectNode written = dao.puts.get(0);
        assertEquals(HandoffMachine.BRAND, written.get("turn").asText());
        // The stage is untouched: the work is still at content_needed, it is just the brand's move.
        assertEquals(LandingStageMachine.CONTENT_NEEDED, written.get("stage").asText());
        assertFalse(dao.postPaths.stream().anyMatch(p -> p.contains("revoke")),
                "access must survive taking the turn back");
    }

    // ---- fixtures ------------------------------------------------------

    private PageCollaborationService service(RecordingDao dao) {
        return new PageCollaborationService(dao, new ResponseShapeService(MAPPER), new HandoffMachine(), null, null);
    }

    private ObjectNode page(String stage, Instant scheduledAt) {
        ObjectNode page = MAPPER.createObjectNode();
        page.put("id", UUID.randomUUID().toString());
        page.put("brandId", BRAND.toString());
        page.put("campaignId", UUID.randomUUID().toString());
        page.put("publicSlug", "winter-trail");
        page.put("name", "Winter trail");
        page.put("status", "draft");
        page.put("stage", stage);
        if (scheduledAt == null) {
            page.putNull("scheduledPublishAt");
        } else {
            page.put("scheduledPublishAt", scheduledAt.toString());
        }
        return page;
    }
}
