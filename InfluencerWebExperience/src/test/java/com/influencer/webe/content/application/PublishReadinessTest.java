package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.PlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * The pre-publish advisory (roadmap PR-39).
 *
 * <p><b>The distinction being pinned is advisory vs refusal.</b> Publishing without a coupon is a
 * legitimate thing to do — a brand-awareness launch or an announcement was never meant to carry an
 * offer — so it must produce a warning the user can read and overrule, never a block. An empty page
 * is the opposite: there is nothing to serve, so it is refused. Conflating the two would either
 * stop real work or publish blank pages, and both have been shipped by products that guessed.
 *
 * <p>Also covers the publishability of a section-authored page, which had to change: before PR-39
 * a page with content in {@code sections} and nothing in {@code document} or {@code blocks} read as
 * empty, so the only editor the brand was given produced pages the publish button rejected.
 */
class PublishReadinessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID PAGE = UUID.randomUUID();
    private static final UUID CAMPAIGN = UUID.randomUUID();

    /** Serves one page and a coupon list, which is all a readiness check reads. */
    private static class StubDao extends DaoGatewayClient {

        private final JsonNode page;
        private final int coupons;
        boolean couponsThrow;

        StubDao(JsonNode page, int coupons) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
            this.page = page;
            this.coupons = coupons;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path != null && path.startsWith("/landing-templates/")) {
                return page;
            }
            if (path != null && path.startsWith("/influencer-campaign-codes")) {
                if (couponsThrow) {
                    throw new IllegalStateException("coupon service unavailable");
                }
                ArrayNode list = MAPPER.createArrayNode();
                for (int i = 0; i < coupons; i++) {
                    list.addObject().put("id", UUID.randomUUID().toString());
                }
                return list;
            }
            return null;
        }

        final List<String> stageWrites = new ArrayList<>();
        final List<String> statusWrites = new ArrayList<>();

        /** Records the write instead of performing it; the transition log and card sync too. */
        @Override
        public JsonNode put(String path, JsonNode body) {
            if (path != null && path.startsWith("/landing-templates/") && body.hasNonNull("stage")) {
                stageWrites.add(body.get("stage").asText());
                statusWrites.add(body.path("status").asText(""));
                // Reflect the write back, so the next hop reads the stage this one just set.
                ((ObjectNode) page).put("stage", body.get("stage").asText());
            }
            return body;
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            return body;
        }
    }

    private ObjectNode page(UUID brandId) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("id", PAGE.toString());
        p.put("brandId", brandId.toString());
        p.put("campaignId", CAMPAIGN.toString());
        p.put("name", "Spring launch");
        // changeStage restates the whole row on the PUT, so the fixture needs the columns it
        // reads back — not just the ones the readiness check looks at.
        p.put("publicSlug", "c-spring");
        return p;
    }

    private LandingStageService serviceFor(StubDao dao) {
        return new LandingStageService(dao, new ResponseShapeService(MAPPER),
                new LandingStageMachine(), null, new PlatformMetrics(new SimpleMeterRegistry()), null);
    }

    // ---- the warning -----------------------------------------------------

    @Test
    @DisplayName("a page with no coupon warns, and offers link tracking instead")
    void warnsWhenNoCoupon() {
        JsonNode out = serviceFor(new StubDao(page(BRAND), 0)).publishReadiness(BRAND, PAGE);

        assertThat(out.path("trackable").asBoolean()).isFalse();
        assertThat(out.path("couponCount").asInt()).isZero();
        assertThat(out.path("warnings")).hasSize(1);

        JsonNode w = out.path("warnings").get(0);
        assertThat(w.path("code").asText()).isEqualTo("no_coupon");
        assertThat(w.path("canTagLinks").asBoolean()).isTrue();
        // The message must say what is lost AND what still works, or it reads as "tracking is
        // broken" when visits are in fact still counted.
        assertThat(w.path("message").asText()).contains("cannot be traced").contains("Visits");
        assertThat(w.path("suggestion").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a page with a coupon is trackable and warns about nothing")
    void noWarningWhenCouponExists() {
        JsonNode out = serviceFor(new StubDao(page(BRAND), 1)).publishReadiness(BRAND, PAGE);

        assertThat(out.path("trackable").asBoolean()).isTrue();
        assertThat(out.path("couponCount").asInt()).isEqualTo(1);
        assertThat(out.path("warnings")).isEmpty();
    }

    @Test
    @DisplayName("readiness reports; it never refuses")
    void neverThrowsForAnUntrackablePage() {
        // The whole point: no exception, no 409 — just a report the user can overrule.
        JsonNode out = serviceFor(new StubDao(page(BRAND), 0)).publishReadiness(BRAND, PAGE);

        assertThat(out.path("warnings")).isNotEmpty();
    }

    @Test
    @DisplayName("a coupon-service outage does not break the publish button")
    void degradesWhenCouponLookupFails() {
        StubDao dao = new StubDao(page(BRAND), 0);
        dao.couponsThrow = true;

        JsonNode out = serviceFor(dao).publishReadiness(BRAND, PAGE);

        // Best-effort: it reports "no coupon" rather than propagating the failure. Being wrong
        // costs one spurious advisory; throwing would block a publish for an unrelated outage.
        assertThat(out.path("couponCount").asInt()).isZero();
    }

    // ---- publishability of a section-authored page ------------------------

    /** A page whose only content is `sections`, as the new editor produces. */
    private ObjectNode sectionOnlyPage() {
        return sectionOnlyPage("ready_to_publish");
    }

    private ObjectNode sectionOnlyPage(String stage) {
        ObjectNode p = page(BRAND);
        p.put("stage", stage);
        // Arrives as a JSON *string*, the way the DAO returns jsonb — the shape the check has to
        // cope with, and the one the equivalent document/blocks logic was careful about.
        p.put("sections", "[{\"type\":\"hero\",\"fields\":{\"headline\":\"New season\"}}]");
        p.put("blocks", "[]");
        p.putNull("document");
        return p;
    }

    @Test
    @DisplayName("a page authored only in the section editor can be published")
    void sectionOnlyPageIsPublishable() {
        StubDao dao = new StubDao(sectionOnlyPage(), 1);

        // Reaching `published` runs the publishable check. Before PR-39 taught it about
        // `sections`, this threw "This page has no content yet".
        JsonNode out = serviceFor(dao).changeStage(BRAND, PAGE, "published", "builder", "k1");

        assertThat(out).isNotNull();
    }

    @Test
    @DisplayName("a page with genuinely nothing in it is still refused")
    void emptyPageIsStillRefused() {
        ObjectNode empty = page(BRAND);
        empty.put("stage", "ready_to_publish");
        empty.put("sections", "[]");
        empty.put("blocks", "[]");
        empty.putNull("document");

        assertThatThrownBy(() -> serviceFor(new StubDao(empty, 1))
                .changeStage(BRAND, PAGE, "published", "builder", "k2"))
                .hasMessageContaining("no content");
    }

    // ---- publish now -------------------------------------------------------

    @Test
    @DisplayName("a draft page publishes in one command, walking the stages it must pass")
    void publishNowWalksTheStages() {
        // The stage machine has no draft -> published edge on purpose. "Publish now" must not
        // become a reason to add one, so it walks the shortest legal path instead.
        StubDao dao = new StubDao(sectionOnlyPage("draft"), 1);

        JsonNode out = serviceFor(dao).publishNow(BRAND, PAGE, "builder");

        assertThat(out).isNotNull();
        // Every hop is a real transition, so each one is audited rather than skipped.
        assertThat(dao.stageWrites).contains("approved", "ready_to_publish", "published");
    }

    @Test
    @DisplayName("publishing sets the status, not just the stage")
    void publishNowSetsStatus() {
        // A page in the Published column that still answers 404 is the bug this pins: the stage
        // and the public status have to move together.
        StubDao dao = new StubDao(sectionOnlyPage("draft"), 1);

        serviceFor(dao).publishNow(BRAND, PAGE, "builder");

        assertThat(dao.statusWrites).contains("published");
    }

    @Test
    @DisplayName("publishing an already-published page is not an error")
    void publishNowIsIdempotent() {
        // A double-click is not a mistake worth a 409.
        StubDao dao = new StubDao(sectionOnlyPage("published"), 1);

        JsonNode out = serviceFor(dao).publishNow(BRAND, PAGE, "builder");

        assertThat(out).isNotNull();
        assertThat(dao.stageWrites).isEmpty();
    }

    @Test
    @DisplayName("an empty page is still refused, however it is published")
    void publishNowStillRefusesEmptyPages() {
        ObjectNode empty = page(BRAND);
        empty.put("stage", "draft");
        empty.put("sections", "[]");
        empty.put("blocks", "[]");
        empty.putNull("document");

        assertThatThrownBy(() -> serviceFor(new StubDao(empty, 1)).publishNow(BRAND, PAGE, "builder"))
                .hasMessageContaining("no content");
    }

    @Test
    @DisplayName("another brand's page is not found, not forbidden")
    void doesNotLeakOtherBrandsPages() {
        StubDao dao = new StubDao(page(UUID.randomUUID()), 3);

        assertThatThrownBy(() -> serviceFor(dao).publishReadiness(BRAND, PAGE))
                .hasMessageContaining("404")
                .hasMessageContaining("not found");
    }
}
