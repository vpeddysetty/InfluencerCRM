package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.application.BrandAccessPort;
import com.influencer.webe.security.AccountRole;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where else an agency already works with the same creator (roadmap PR-66).
 *
 * <p>Every test here is really about one thing: <b>the answer is bounded by the caller's own
 * brands</b>. "Which brands work with this handle" is a question whose answer belongs to other
 * customers, and the difference between the useful feature and a cross-tenant leak is entirely in
 * which brand list reaches the query.
 */
class SharedCreatorServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID BRAND_A = UUID.randomUUID();
    private static final UUID BRAND_B = UUID.randomUUID();
    private static final UUID STRANGER_BRAND = UUID.randomUUID();
    private static final UUID CREATOR_A = UUID.randomUUID();

    private static final class StubAccess implements BrandAccessPort {
        private final List<BrandAccess> brands;

        StubAccess(List<BrandAccess> brands) {
            this.brands = brands;
        }

        @Override
        public List<BrandAccess> findAccessibleBrandsForPort(UUID userId) {
            return brands;
        }
    }

    /** Serves the creator record, and records exactly what the cross-brand query was asked. */
    private static final class StubDao extends DaoGatewayClient {
        private final JsonNode creator;
        private final JsonNode acrossBrands;
        final List<Map<String, String>> queries = new ArrayList<>();

        StubDao(JsonNode creator, JsonNode acrossBrands) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    return null;
                }
            }, null);
            this.creator = creator;
            this.acrossBrands = acrossBrands;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.contains("across-brands")) {
                queries.add(query == null ? Map.of() : new LinkedHashMap<>(query));
                return acrossBrands;
            }
            return creator;
        }
    }

    private ObjectNode creatorRow(UUID id, UUID brandId, String handle, String rate) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("id", id.toString());
        row.put("brandId", brandId.toString());
        row.put("handle", handle);
        row.put("platform", "instagram");
        if (rate != null) {
            row.put("preferredRate", rate);
        }
        return row;
    }

    private BrandAccessPort.BrandAccess access(UUID brandId, String name) {
        return new BrandAccessPort.BrandAccess(brandId, name, UUID.randomUUID(), "agency",
                AccountRole.OWNER);
    }

    private SharedCreatorService service(StubDao dao, StubAccess access) {
        return new SharedCreatorService(dao, access, new ResponseShapeService(MAPPER));
    }

    @Test
    @DisplayName("another of the caller's brands working with the same creator is reported, with its rate")
    void reportsAnotherOwnBrand() {
        ArrayNode across = MAPPER.createArrayNode();
        across.add(creatorRow(UUID.randomUUID(), BRAND_B, "someone", "750.00"));
        StubDao dao = new StubDao(creatorRow(CREATOR_A, BRAND_A, "someone", "500.00"), across);

        JsonNode out = service(dao, new StubAccess(List.of(access(BRAND_A, "Aurora"), access(BRAND_B, "Bolt"))))
                .alsoWorkingWith(USER, BRAND_A, CREATOR_A);

        assertEquals(1, out.get("alsoAt").size());
        assertEquals("Bolt", out.get("alsoAt").get(0).get("brandName").asText());
        // The rate is the useful half: an agency about to negotiate wants to know what it already
        // pays this person elsewhere.
        assertEquals("750.00", out.get("alsoAt").get(0).get("preferredRate").asText());
    }

    @Test
    @DisplayName("ONLY the caller's own brands are ever asked about")
    void theQueryIsBoundedByTheCallersBrands() {
        // The security property. The DAO sorts rows within the list it is given and computes
        // nothing, so this list IS the tenant boundary.
        StubDao dao = new StubDao(creatorRow(CREATOR_A, BRAND_A, "someone", null),
                MAPPER.createArrayNode());

        service(dao, new StubAccess(List.of(access(BRAND_A, "Aurora"), access(BRAND_B, "Bolt"))))
                .alsoWorkingWith(USER, BRAND_A, CREATOR_A);

        String asked = dao.queries.get(0).get("brandIds");
        assertTrue(asked.contains(BRAND_B.toString()), "the caller's other brand must be searched");
        assertFalse(asked.contains(STRANGER_BRAND.toString()), "no other tenant may be searched");
        assertFalse(asked.contains(BRAND_A.toString()),
                "the brand being viewed is excluded -- telling someone the record they are looking "
                        + "at exists is not an insight");
    }

    @Test
    @DisplayName("a row for a brand outside the caller's list never renders, even if the DAO returned it")
    void rowsOutsideTheScopeAreDropped() {
        // Belt and braces over the query above. This is the one place a scoping mistake would be
        // visible to a user rather than caught by a test, so it is checked twice on purpose.
        ArrayNode across = MAPPER.createArrayNode();
        across.add(creatorRow(UUID.randomUUID(), STRANGER_BRAND, "someone", "999.00"));
        StubDao dao = new StubDao(creatorRow(CREATOR_A, BRAND_A, "someone", null), across);

        // BRAND_B is in the caller's list so the method actually REACHES the row filter. An
        // earlier version gave the caller only BRAND_A -- which is excluded as the brand being
        // viewed -- leaving nothing reachable, so the method returned early and the test passed
        // with the filter deleted. It was green while exercising none of the code it named.
        JsonNode out = service(dao, new StubAccess(List.of(access(BRAND_A, "Aurora"), access(BRAND_B, "Bolt"))))
                .alsoWorkingWith(USER, BRAND_A, CREATOR_A);

        // Asserted on the whole payload rather than one field: a row for an unreachable brand has
        // no name to look up, so it would render WITHOUT brandName and still carry the rate.
        assertEquals(0, out.get("alsoAt").size(),
                "a row outside the caller's brands must not render at all: " + out);
        assertFalse(out.toString().contains("999.00"),
                "another tenant's rate must never appear in the response: " + out);
    }

    @Test
    @DisplayName("another brand's creator id answers nothing, rather than confirming it exists")
    void refusesACreatorTheCallerDoesNotOwn() {
        // Otherwise a creator id becomes a probe for which handles exist elsewhere.
        StubDao dao = new StubDao(creatorRow(CREATOR_A, STRANGER_BRAND, "someone", null),
                MAPPER.createArrayNode());

        JsonNode out = service(dao, new StubAccess(List.of(access(BRAND_A, "Aurora"))))
                .alsoWorkingWith(USER, BRAND_A, CREATOR_A);

        assertEquals(0, out.get("alsoAt").size());
        assertTrue(dao.queries.isEmpty(), "it must not even ask");
    }

    @Test
    @DisplayName("a caller with one brand asks nothing and gets an empty answer")
    void singleBrandCallerSkipsTheQuery() {
        // The common case for a brand rather than an agency: there is nowhere else to look, and a
        // query with an empty brand list must never become an unbounded one.
        StubDao dao = new StubDao(creatorRow(CREATOR_A, BRAND_A, "someone", null),
                MAPPER.createArrayNode());

        JsonNode out = service(dao, new StubAccess(List.of(access(BRAND_A, "Aurora"))))
                .alsoWorkingWith(USER, BRAND_A, CREATOR_A);

        assertEquals(0, out.get("alsoAt").size());
        assertTrue(dao.queries.isEmpty());
    }

    @Test
    @DisplayName("a missing rate stays missing rather than becoming zero")
    void absentRateIsNotZero() {
        // "No rate recorded" and "works for nothing" are very different things to carry into a
        // negotiation, and the second is the one somebody would act on.
        ArrayNode across = MAPPER.createArrayNode();
        across.add(creatorRow(UUID.randomUUID(), BRAND_B, "someone", null));
        StubDao dao = new StubDao(creatorRow(CREATOR_A, BRAND_A, "someone", null), across);

        JsonNode out = service(dao, new StubAccess(List.of(access(BRAND_A, "Aurora"), access(BRAND_B, "Bolt"))))
                .alsoWorkingWith(USER, BRAND_A, CREATOR_A);

        assertFalse(out.get("alsoAt").get(0).has("preferredRate"),
                "an unrecorded rate must be absent, not 0");
    }
}
