package com.influencer.webe.creator.application;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a brand may do with a creator's content, and for how long (roadmap PR-68).
 *
 * <p>The rule underneath every test here is that <b>unset is unknown, never granted</b>. On a legal
 * question failing open is worse than an empty field: an empty field prompts someone to ask, and an
 * affirmative answer stops them. The second theme is ownership — an engagement id from another
 * brand must not become a way to read or edit someone else's terms.
 */
class UsageRightsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID ENGAGEMENT = UUID.randomUUID();

    private static final class StubDao extends DaoGatewayClient {
        private final JsonNode engagement;
        private final JsonNode expiring;
        final List<JsonNode> writes = new ArrayList<>();
        final List<Map<String, String>> queries = new ArrayList<>();

        StubDao(JsonNode engagement, JsonNode expiring) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    return null;
                }
            }, null);
            this.engagement = engagement;
            this.expiring = expiring;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.contains("expiring-rights")) {
                queries.add(query == null ? Map.of() : new LinkedHashMap<>(query));
                return expiring;
            }
            return engagement;
        }

        @Override
        public JsonNode put(String path, JsonNode payload) {
            writes.add(payload);
            return payload;
        }
    }

    private ObjectNode engagementOf(UUID brandId) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("id", ENGAGEMENT.toString());
        row.put("brandId", brandId.toString());
        row.put("campaignId", UUID.randomUUID().toString());
        row.put("creatorId", UUID.randomUUID().toString());
        return row;
    }

    private UsageRightsService service(StubDao dao) {
        return new UsageRightsService(dao, new ResponseShapeService(MAPPER));
    }

    private ObjectNode scopes(String... values) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("usageScopes");
        for (String value : values) {
            array.add(value);
        }
        return payload;
    }

    @Test
    @DisplayName("agreed scopes are recorded, normalised to the vocabulary")
    void recordsScopes() {
        StubDao dao = new StubDao(engagementOf(BRAND), null);

        service(dao).record(BRAND, ENGAGEMENT, scopes("Organic", " PAID_AMPLIFICATION "));

        JsonNode written = dao.writes.get(0).get("usageScopes");
        assertEquals("organic", written.get(0).asText());
        assertEquals("paid_amplification", written.get(1).asText());
    }

    @Test
    @DisplayName("an unknown scope is refused, and the message says what is allowed")
    void unknownScopeIsRefused() {
        StubDao dao = new StubDao(engagementOf(BRAND), null);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(dao).record(BRAND, ENGAGEMENT, scopes("billboards")));

        assertTrue(String.valueOf(thrown.getReason()).contains("organic"),
                "the allowed list must be named rather than left to be discovered: " + thrown.getReason());
        assertTrue(dao.writes.isEmpty(), "nothing may be written when the payload is refused");
    }

    @Test
    @DisplayName("another brand's engagement is not found, and nothing is written")
    void refusesAnotherBrand() {
        // Otherwise an engagement id becomes a way to read -- or overwrite -- someone else's terms.
        StubDao dao = new StubDao(engagementOf(OTHER_BRAND), null);

        assertThrows(ResponseStatusException.class,
                () -> service(dao).record(BRAND, ENGAGEMENT, scopes("organic")));

        assertTrue(dao.writes.isEmpty());
    }

    @Test
    @DisplayName("a licence cannot end before it starts")
    void refusesAReversedTerm() {
        // A reversed term is silently unenforceable: every expiry query skips it, so the row that
        // most needs chasing is the one that never appears.
        StubDao dao = new StubDao(engagementOf(BRAND), null);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("rightsStartAt", "2026-06-01T00:00:00Z");
        payload.put("rightsEndAt", "2026-01-01T00:00:00Z");

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(dao).record(BRAND, ENGAGEMENT, payload));

        assertTrue(String.valueOf(thrown.getReason()).contains("cannot end before"));
        assertTrue(dao.writes.isEmpty());
    }

    @Test
    @DisplayName("a perpetual grant — a start with no end — is accepted")
    void perpetualIsValid() {
        // "Perpetual" is a real answer, expressed as a start with no end, and distinct from
        // "not recorded", which is both unset.
        StubDao dao = new StubDao(engagementOf(BRAND), null);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("rightsStartAt", "2026-06-01T00:00:00Z");

        service(dao).record(BRAND, ENGAGEMENT, payload);

        assertEquals(1, dao.writes.size());
    }

    @Test
    @DisplayName("the expiry window is a half-open range starting now")
    void expiryWindowIsBounded() {
        StubDao dao = new StubDao(engagementOf(BRAND), MAPPER.createArrayNode());

        service(dao).expiringWithin(BRAND, 30);

        Map<String, String> asked = dao.queries.get(0);
        assertTrue(asked.containsKey("from") && asked.containsKey("until"),
                "both bounds must reach the DAO: " + asked);
        assertEquals(BRAND.toString(), asked.get("brandId"));
    }

    @Test
    @DisplayName("an empty expiry list is a count of zero, not an error")
    void nothingExpiringIsAnAnswer() {
        StubDao dao = new StubDao(engagementOf(BRAND), MAPPER.createArrayNode());

        JsonNode out = service(dao).expiringWithin(BRAND, 30);

        assertEquals(0, out.get("count").asInt());
        assertEquals(30, out.get("withinDays").asInt());
    }
}
