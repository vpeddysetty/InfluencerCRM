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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saving a page as a reusable template (roadmap PR-39, piece D).
 *
 * <p><b>The property that matters most is what saving strips.</b> The editor strips before it
 * sends, so the user can see what will be kept — but this layer has to do it again, because a
 * template that kept the creator's name would credit the wrong person on the next campaign's
 * PUBLIC page, under the brand's own name. That is a mistake that reaches the outside world, and
 * it must not depend on the client being a current bundle rather than an old one, a replay, or a
 * script.
 */
class BrandPageTemplateServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    /** Captures what would be written, and serves a canned list back. */
    private static class RecordingDao extends DaoGatewayClient {

        final List<JsonNode> posted = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        JsonNode existing = MAPPER.createArrayNode();

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
            return existing;
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            posted.add(body);
            // Echo back in the DAO's shape: jsonb comes back as a STRING, which is what the
            // projection has to cope with.
            ObjectNode row = MAPPER.createObjectNode();
            row.put("id", UUID.randomUUID().toString());
            row.put("name", body.path("name").asText());
            row.put("sections", body.path("sections").asText());
            row.put("createdAt", "2026-08-24T10:00:00Z");
            return row;
        }

        @Override
        public void delete(String path) {
            deleted.add(path);
        }
    }

    private BrandPageTemplateService serviceFor(RecordingDao dao) {
        return new BrandPageTemplateService(dao, new ResponseShapeService(MAPPER));
    }

    private ObjectNode section(String type) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("type", type);
        s.put("variant", "centred");
        s.putObject("fields");
        return s;
    }

    private ObjectNode payloadWith(String name, ObjectNode... sections) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("name", name);
        ArrayNode list = payload.putArray("sections");
        for (ObjectNode s : sections) {
            list.add(s);
        }
        return payload;
    }

    /** The sections actually written, parsed back out of the jsonb string. */
    private JsonNode writtenSections(RecordingDao dao) throws Exception {
        return MAPPER.readTree(dao.posted.get(0).path("sections").asText());
    }

    // ---- what saving strips ----------------------------------------------

    @Test
    @DisplayName("the creator's identity is cleared, but their words are kept")
    void stripsCreatorIdentity() throws Exception {
        ObjectNode creator = section("creator");
        ObjectNode fields = (ObjectNode) creator.get("fields");
        fields.put("quote", "I wear these every day");
        fields.put("name", "Maya Okonjo");
        fields.put("handle", "mayawears");
        fields.put("platform", "Instagram");
        fields.put("portrait", "https://cdn.example.com/maya.jpg");

        RecordingDao dao = new RecordingDao();
        serviceFor(dao).save(BRAND, USER, payloadWith("Spring shape", creator));

        JsonNode saved = writtenSections(dao).get(0).get("fields");
        assertThat(saved.get("quote").asText()).isEqualTo("I wear these every day");
        assertThat(saved.get("name").asText()).isEmpty();
        assertThat(saved.get("handle").asText()).isEmpty();
        assertThat(saved.get("platform").asText()).isEmpty();
        assertThat(saved.get("portrait").asText()).isEmpty();
    }

    @Test
    @DisplayName("coupon tokens survive — that is what a token is for")
    void keepsTokens() throws Exception {
        ObjectNode offer = section("offer");
        ((ObjectNode) offer.get("fields")).put("supporting", "Use {{coupon.code}} at checkout");

        RecordingDao dao = new RecordingDao();
        serviceFor(dao).save(BRAND, USER, payloadWith("Offer shape", offer));

        assertThat(writtenSections(dao).get(0).get("fields").get("supporting").asText())
                .isEqualTo("Use {{coupon.code}} at checkout");
    }

    @Test
    @DisplayName("a section type this build does not know is passed through, not dropped")
    void passesUnknownTypesThrough() throws Exception {
        ObjectNode odd = section("carousel3d");
        ((ObjectNode) odd.get("fields")).put("body", "from a newer build");

        RecordingDao dao = new RecordingDao();
        serviceFor(dao).save(BRAND, USER, payloadWith("Odd", odd));

        // Dropping what it does not recognise would quietly reshape a template saved by a newer
        // deployment and read back by an older one.
        JsonNode saved = writtenSections(dao);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).get("fields").get("body").asText()).isEqualTo("from a newer build");
    }

    @Test
    @DisplayName("stripped fields are emptied, not removed")
    void emptiesRatherThanRemoves() throws Exception {
        ObjectNode creator = section("creator");
        ((ObjectNode) creator.get("fields")).put("name", "Maya Okonjo");

        RecordingDao dao = new RecordingDao();
        serviceFor(dao).save(BRAND, USER, payloadWith("Shape", creator));

        // The editor renders inputs from the schema, but a present-and-empty field also tells a
        // reader of the stored JSON that the omission was deliberate rather than never set.
        assertThat(writtenSections(dao).get(0).get("fields").has("name")).isTrue();
    }

    // ---- validation --------------------------------------------------------

    @Test
    @DisplayName("a template with no sections is refused")
    void refusesEmptyTemplate() {
        // Storing one would put an entry in the picker whose only effect is to produce a blank page.
        assertThatThrownBy(() -> serviceFor(new RecordingDao())
                .save(BRAND, USER, payloadWith("Empty")))
                .hasMessageContaining("nothing on this page");
    }

    @Test
    @DisplayName("a template with no name is refused")
    void refusesUnnamedTemplate() {
        assertThatThrownBy(() -> serviceFor(new RecordingDao())
                .save(BRAND, USER, payloadWith("   ", section("hero"))))
                .hasMessageContaining("name is required");
    }

    // ---- replacing vs creating ---------------------------------------------

    @Test
    @DisplayName("an existing name is recognised regardless of case")
    void existsByNameIsCaseInsensitive() {
        RecordingDao dao = new RecordingDao();
        ArrayNode rows = MAPPER.createArrayNode();
        rows.addObject().put("id", UUID.randomUUID().toString()).put("name", "Spring Launch");
        dao.existing = rows;

        BrandPageTemplateService service = serviceFor(dao);

        // The unique index is on lower(name), so a check that disagreed would fire the plan limit
        // on a save the storage layer was about to accept as a replacement.
        assertThat(service.existsByName(BRAND, "spring launch")).isTrue();
        assertThat(service.existsByName(BRAND, "  SPRING LAUNCH  ")).isTrue();
        assertThat(service.existsByName(BRAND, "Autumn")).isFalse();
    }

    // ---- tenancy -----------------------------------------------------------

    @Test
    @DisplayName("deleting scopes the request by brand, so a guessed id reaches nothing")
    void deleteIsBrandScoped() {
        RecordingDao dao = new RecordingDao();
        UUID templateId = UUID.randomUUID();

        serviceFor(dao).delete(BRAND, templateId);

        assertThat(dao.deleted).hasSize(1);
        assertThat(dao.deleted.get(0)).contains(templateId.toString()).contains("brandId=" + BRAND);
    }

    @Test
    @DisplayName("the list projects jsonb text back into real JSON for the UI")
    void listParsesSections() {
        RecordingDao dao = new RecordingDao();
        ArrayNode rows = MAPPER.createArrayNode();
        rows.addObject()
                .put("id", UUID.randomUUID().toString())
                .put("name", "Spring")
                .put("sections", "[{\"type\":\"hero\",\"fields\":{\"headline\":\"Hi\"}}]");
        dao.existing = rows;

        JsonNode out = serviceFor(dao).list(BRAND);

        // A string here would reach the editor as text and render nothing, which is the same class
        // of silent failure the projection allow-list exists to prevent.
        assertThat(out.get(0).get("sections").isArray()).isTrue();
        assertThat(out.get(0).get("sections").get(0).get("type").asText()).isEqualTo("hero");
    }
}
