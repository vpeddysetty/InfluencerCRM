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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a creator's save must carry, and what it must leave alone (roadmap OP-18).
 *
 * <p>These are the first tests this service has had, and both cases below were live defects rather
 * than hypotheticals. Neither is visible from reading the happy path: the save returned 200 in both
 * cases, and the damage showed up somewhere else — the creator's work missing from the page, or a
 * scheduled launch that never fired.
 *
 * <p>The assertions are all on the <b>body sent to the DAO</b>, because that is where both bugs
 * lived. A partial write to {@code landing_templates} is only correct relative to what the DAO does
 * with an omitted field, and that differs per column — see {@link LandingTemplateWrites}.
 */
class PageCollaborationSaveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Serves fixed rows and records every write, so a test can assert what was sent. */
    private static class RecordingDao extends DaoGatewayClient {

        final List<ObjectNode> puts = new ArrayList<>();
        private final JsonNode page;
        private final JsonNode grants;
        private final JsonNode links;

        RecordingDao(JsonNode page, JsonNode grants, JsonNode links) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
            this.page = page;
            this.grants = grants;
            this.links = links;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            // Three distinct reads, and they must stay distinct: access is deliberately hung off
            // BOTH the grant row and the underlying identity link, so a stub collapsing them would
            // pass tests that the real service would refuse.
            if (path.startsWith("/landing-page-collaborators")) {
                return grants;
            }
            if (path.startsWith("/creator-identities/")) {
                return links;
            }
            return page;
        }

        @Override
        public JsonNode put(String path, JsonNode body) {
            puts.add((ObjectNode) body);
            // Echo the stored page merged with the write, which is what the real DAO returns.
            ObjectNode saved = page.deepCopy();
            body.fieldNames().forEachRemaining(field -> saved.set(field, body.get(field)));
            return saved;
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            return MAPPER.createObjectNode();
        }
    }

    @Test
    @DisplayName("a creator's section edits reach the DAO")
    void collaboratorSaveCarriesSections() {
        // The defect this pins: saveAsCollaborator forwarded `document` and `blocks` but not
        // `sections`. Since PR-39 switched production to the section editor, `sections` is the
        // ONLY column a creator actually edits — so every creator save was accepted and then had
        // no effect. The DAO null-guards the column, so the work was not destroyed; it was
        // ignored, which is harder to notice because the save reported success.
        UUID creator = UUID.randomUUID();
        UUID template = UUID.randomUUID();
        ObjectNode page = storedPage(template, null);
        RecordingDao dao = new RecordingDao(page, grantFor(creator, template, page), confirmedLink(page));

        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode sections = payload.putArray("sections");
        ObjectNode hero = sections.addObject();
        hero.put("type", "hero");
        hero.put("headline", "Written by the creator");

        service(dao).saveAsCollaborator(creator, template, payload);

        assertEquals(1, dao.puts.size(), "the save must reach the DAO");
        ObjectNode sent = dao.puts.get(0);
        assertTrue(sent.hasNonNull("sections"), "the creator's sections must be forwarded");
        assertTrue(sent.get("sections").asText().contains("Written by the creator"),
                "the forwarded sections must be the ones the creator wrote");
    }

    @Test
    @DisplayName("a creator's save does not cancel the brand's scheduled launch")
    void collaboratorSavePreservesScheduledPublish() {
        // The worst of the set, because nothing surfaces it. The DAO deliberately does NOT
        // null-guard scheduledPublishAt — clearing it is how the scheduler consumes a fired
        // publish — so a PUT omitting it cancels the schedule. The controller comment states the
        // resulting obligation ("every BFF caller writing this row restates it"); this caller did
        // not, so one creator save silently un-scheduled the launch with no error anywhere.
        Instant launch = Instant.parse("2026-09-01T09:00:00Z");
        UUID creator = UUID.randomUUID();
        UUID template = UUID.randomUUID();
        ObjectNode page = storedPage(template, launch);
        RecordingDao dao = new RecordingDao(page, grantFor(creator, template, page), confirmedLink(page));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("sections").addObject().put("type", "hero");

        service(dao).saveAsCollaborator(creator, template, payload);

        ObjectNode sent = dao.puts.get(0);
        assertTrue(sent.hasNonNull("scheduledPublishAt"),
                "the pending schedule must be restated, or the DAO's PUT clears it");
        assertEquals(launch.toString(), sent.get("scheduledPublishAt").asText());
    }

    @Test
    @DisplayName("an unscheduled page stays unscheduled")
    void collaboratorSaveInventsNoSchedule() {
        // The mirror of the test above: carrying a value forward must not become writing one.
        // A page with no pending publish must not acquire one from a save.
        UUID creator = UUID.randomUUID();
        UUID template = UUID.randomUUID();
        ObjectNode page = storedPage(template, null);
        RecordingDao dao = new RecordingDao(page, grantFor(creator, template, page), confirmedLink(page));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("sections").addObject().put("type", "hero");

        service(dao).saveAsCollaborator(creator, template, payload);

        assertFalse(dao.puts.get(0).hasNonNull("scheduledPublishAt"),
                "a save must not schedule a publish nobody asked for");
    }

    @Test
    @DisplayName("a collaborator still cannot publish by saving")
    void collaboratorSaveCannotChangeStatusOrStage() {
        // Unchanged behaviour, pinned because the fixes above widened what this method forwards.
        // The next person adding a field here needs the boundary written down as a test, not as a
        // comment: content moves, authority does not.
        UUID creator = UUID.randomUUID();
        UUID template = UUID.randomUUID();
        ObjectNode page = storedPage(template, null);
        RecordingDao dao = new RecordingDao(page, grantFor(creator, template, page), confirmedLink(page));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("status", "published");
        payload.put("stage", "published");
        payload.put("publicSlug", "stolen-slug");
        payload.putArray("sections").addObject().put("type", "hero");

        service(dao).saveAsCollaborator(creator, template, payload);

        ObjectNode sent = dao.puts.get(0);
        assertEquals("draft", sent.get("status").asText(), "a collaborator cannot publish");
        assertEquals("draft", sent.get("stage").asText(), "a collaborator cannot move the stage");
        assertEquals("winter-trail", sent.get("publicSlug").asText(), "the slug comes from the stored page");
    }

    // ---- fixtures ------------------------------------------------------

    private PageCollaborationService service(RecordingDao dao) {
        return new PageCollaborationService(dao, new ResponseShapeService(MAPPER));
    }

    private ObjectNode storedPage(UUID templateId, Instant scheduledAt) {
        ObjectNode page = MAPPER.createObjectNode();
        page.put("id", templateId.toString());
        page.put("brandId", UUID.randomUUID().toString());
        page.put("campaignId", UUID.randomUUID().toString());
        page.put("publicSlug", "winter-trail");
        page.put("name", "Winter trail");
        page.put("status", "draft");
        page.put("stage", "draft");
        if (scheduledAt == null) {
            page.putNull("scheduledPublishAt");
        } else {
            page.put("scheduledPublishAt", scheduledAt.toString());
        }
        return page;
    }

    /**
     * The edit grant. Its {@code brandId} must match the page's, because that is the value
     * {@code requireEditRights} carries into the link check.
     */
    private ArrayNode grantFor(UUID creatorIdentityId, UUID templateId, ObjectNode page) {
        ArrayNode rows = MAPPER.createArrayNode();
        ObjectNode row = rows.addObject();
        row.put("id", UUID.randomUUID().toString());
        row.put("landingTemplateId", templateId.toString());
        row.put("creatorIdentityId", creatorIdentityId.toString());
        row.put("brandId", page.get("brandId").asText());
        row.put("rights", "edit");
        return rows;
    }

    /** The confirmed identity link the grant is re-checked against on every edit. */
    private ArrayNode confirmedLink(ObjectNode page) {
        ArrayNode links = MAPPER.createArrayNode();
        links.addObject()
                .put("brandId", page.get("brandId").asText())
                .put("status", "confirmed");
        return links;
    }
}
