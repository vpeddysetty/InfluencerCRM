package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filling a brief from the brand, campaign and creator records (roadmap PR-35).
 *
 * <p>Two properties matter more than the happy path, and both are here: <b>the user's own words are
 * never overwritten</b>, and <b>another tenant's records are never read into a page</b>. The first
 * is a usability promise — a system that argues with what you typed is worse than one that asks
 * for more. The second is a tenancy boundary: campaign names and creator handles are customer data,
 * and a page that quietly acquired a competitor's would be a leak, not a convenience.
 */
class BriefEnricherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID CAMPAIGN = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();

    /** A DAO that answers from a fixed map, and records what was asked. */
    private static class StubDao extends DaoGatewayClient {

        private final Map<String, JsonNode> responses = new LinkedHashMap<>();
        private RuntimeException failure;

        StubDao() {
            // The real constructor calls httpClientFactory.create(), so a factory is required
            // even though no request is ever made — get() is overridden. Everything else on the
            // real client would NPE, which is the correct outcome here: this test asserts the
            // enricher makes no call other than the reads it declares.
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    // Overridden because the real one builds an mTLS context from keystore files
                    // that do not exist in a unit test. No request is made either way.
                    return null;
                }
            }, null);
        }

        StubDao with(String path, JsonNode response) {
            responses.put(path, response);
            return this;
        }

        StubDao failing(RuntimeException e) {
            this.failure = e;
            return this;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (failure != null) {
                throw failure;
            }
            return responses.get(path);
        }
    }

    private ObjectNode brand(String name) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", BRAND.toString());
        node.put("name", name);
        return node;
    }

    private ObjectNode campaign(UUID owner, String name, String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", CAMPAIGN.toString());
        node.put("brandId", owner.toString());
        node.put("name", name);
        node.put("campaignType", type);
        return node;
    }

    private ObjectNode creator(UUID owner, String name, String handle, String platform) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", CREATOR.toString());
        node.put("brandId", owner.toString());
        node.put("name", name);
        node.put("handle", handle);
        node.put("platform", platform);
        return node;
    }

    @Test
    @DisplayName("it fills brand, campaign and creator detail the user did not type")
    void fillsFromRecords() {
        StubDao dao = new StubDao()
                .with("/tenancy/brands/" + BRAND, brand("Trailhead"))
                .with("/campaigns/" + CAMPAIGN, campaign(BRAND, "Winter Trails", "product_launch"))
                .with("/creators/" + CREATOR, creator(BRAND, "Sam Okonjo", "northbound", "instagram"));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("goal", "Launch the winter collection");
        payload.put("campaignId", CAMPAIGN.toString());
        payload.put("creatorId", CREATOR.toString());

        new BriefEnricher(dao).enrich(BRAND, payload);

        assertEquals("Trailhead", payload.get("brandName").asText());
        assertEquals("Winter Trails", payload.get("campaignName").asText());
        assertEquals("product_launch", payload.get("campaignType").asText());
        assertEquals("Sam Okonjo", payload.get("creatorName").asText());
        assertEquals("instagram", payload.get("creatorPlatform").asText());
        // Normalised to the form the copy uses, so a record storing a bare handle and one storing
        // "@handle" produce the same page.
        assertEquals("@northbound", payload.get("creatorHandle").asText());
        assertTrue(payload.get("enrichedFields").size() > 0,
                "the user is told which details were filled in for them");
    }

    @Test
    @DisplayName("what the user typed always wins")
    void neverOverwritesTheUsersOwnWords() {
        // A user who typed a campaign type meant it. Overwriting from the record would be the
        // system arguing with them about their own page.
        StubDao dao = new StubDao()
                .with("/campaigns/" + CAMPAIGN, campaign(BRAND, "Winter Trails", "product_launch"))
                .with("/creators/" + CREATOR, creator(BRAND, "Sam Okonjo", "northbound", "instagram"));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("goal", "Launch the winter collection");
        payload.put("campaignId", CAMPAIGN.toString());
        payload.put("creatorId", CREATOR.toString());
        payload.put("campaignType", "waitlist");
        payload.put("creatorHandle", "@someone-else");

        new BriefEnricher(dao).enrich(BRAND, payload);

        assertEquals("waitlist", payload.get("campaignType").asText());
        assertEquals("@someone-else", payload.get("creatorHandle").asText());
    }

    @Test
    @DisplayName("records belonging to another brand are ignored, not read into the page")
    void refusesCrossTenantRecords() {
        // The ids are guessable and the tenant comes from the verified token, so the ownership
        // check is the only thing standing between a curious caller and another brand's data.
        StubDao dao = new StubDao()
                .with("/campaigns/" + CAMPAIGN, campaign(OTHER_BRAND, "Someone Else's Launch", "waitlist"))
                .with("/creators/" + CREATOR, creator(OTHER_BRAND, "Their Creator", "theirs", "tiktok"));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("goal", "Launch the winter collection");
        payload.put("campaignId", CAMPAIGN.toString());
        payload.put("creatorId", CREATOR.toString());

        new BriefEnricher(dao).enrich(BRAND, payload);

        assertFalse(payload.has("campaignName"), "another brand's campaign name must not leak");
        assertFalse(payload.has("creatorName"), "another brand's creator must not leak");
        assertFalse(payload.has("creatorHandle"));
    }

    @Test
    @DisplayName("a DAO failure degrades the brief rather than failing the request")
    void daoFailureIsNotFatal() {
        // Enrichment is an improvement. Losing it should cost the page some specificity, never
        // cost the user their generation.
        StubDao dao = new StubDao().failing(new IllegalStateException("DAO unreachable"));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("goal", "Launch the winter collection");
        payload.put("campaignId", CAMPAIGN.toString());

        new BriefEnricher(dao).enrich(BRAND, payload);

        assertEquals("Launch the winter collection", payload.get("goal").asText());
        assertFalse(payload.has("campaignName"));
    }
}
