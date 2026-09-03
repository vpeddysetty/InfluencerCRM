package com.influencer.webe.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 1099-NEC threshold, and whether someone may be paid (roadmap PR-49).
 *
 * <p>Three behaviours carry the weight. The threshold is "$600 or more", not "over $600" — an
 * off-by-one is a missed filing obligation. The question asked is whether THIS payment crosses it,
 * not whether past ones did, because answering the second while being asked the first is how a form
 * gets chased a day late. And an unreadable total does not block a payout: failing closed would stop
 * a brand paying someone because the DAO hiccuped.
 */
class TaxThresholdServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CREATOR = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static class StubDao extends DaoGatewayClient {
        private final ObjectNode creator;
        private final String paidTotal;
        private final boolean totalReadable;
        private final List<JsonNode> patches = new ArrayList<>();

        StubDao(ObjectNode creator, String paidTotal, boolean totalReadable) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    return null;
                }
            }, null);
            this.creator = creator;
            this.paidTotal = paidTotal;
            this.totalReadable = totalReadable;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.startsWith("/creators/")) {
                return creator;
            }
            if (!totalReadable) {
                throw new IllegalStateException("DAO unreachable");
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.put("paidTotal", paidTotal);
            return out;
        }

        @Override
        public JsonNode patch(String path, JsonNode payload) {
            patches.add(payload);
            return payload;
        }

        List<JsonNode> patches() {
            return patches;
        }
    }

    private ObjectNode creator(UUID owner, String accountId, boolean payoutsEnabled, String formOnFileAt) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("id", CREATOR.toString());
        c.put("brandId", owner.toString());
        if (accountId != null) c.put("stripeAccountId", accountId);
        c.put("payoutsEnabled", payoutsEnabled);
        if (formOnFileAt != null) c.put("taxFormOnFileAt", formOnFileAt);
        return c;
    }

    private TaxThresholdService service(StubDao dao) {
        return new TaxThresholdService(dao, new ResponseShapeService(MAPPER));
    }

    @Test
    @DisplayName("exactly $600 requires a form — the IRS figure is 'or more', not 'over'")
    void thresholdIsInclusive() {
        // The off-by-one that would be a missed filing obligation.
        JsonNode out = service(new StubDao(null, "600.00", true)).assess(BRAND, CREATOR, null);

        assertThat(out.get("formRequired").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("$599.99 does not")
    void underThresholdIsClear() {
        JsonNode out = service(new StubDao(null, "599.99", true)).assess(BRAND, CREATOR, null);

        assertThat(out.get("formRequired").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the question is whether THIS payment crosses it, not whether past ones did")
    void projectsTheAmountBeingPaid() {
        // $400 paid, $250 about to be. Answering on the $400 alone chases the form a day late —
        // after the payment that crossed the line has already gone.
        JsonNode out = service(new StubDao(null, "400.00", true))
                .assess(BRAND, CREATOR, new BigDecimal("250.00"));

        assertThat(out.get("projected").decimalValue()).isEqualByComparingTo("650.00");
        assertThat(out.get("formRequired").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("approaching the threshold is flagged before it is crossed")
    void warnsBeforeBlocking() {
        // The difference between a prompt and a blocker: a creator asked at $600.01 has already
        // been paid $600, and the payout that crossed it is the one that had to wait.
        JsonNode out = service(new StubDao(null, "500.00", true)).assess(BRAND, CREATOR, null);

        assertThat(out.get("approaching").asBoolean()).isTrue();
        assertThat(out.get("formRequired").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("an unreadable total is UNKNOWN, and must not read as zero")
    void unreadableTotalIsNotZero() {
        // A brand shown $0.00 would conclude there is nothing to chase, which is the opposite of
        // what an unreadable total means.
        JsonNode out = service(new StubDao(null, null, false)).assess(BRAND, CREATOR, null);

        assertThat(out.get("known").asBoolean()).isFalse();
        assertThat(out.has("paidThisYear")).isFalse();
    }

    @Test
    @DisplayName("an unreadable total does not block the payout")
    void unreadableTotalDoesNotBlock() {
        // Failing closed would stop a brand paying a creator because the DAO hiccuped. The threshold
        // is a reporting obligation with an annual deadline, not a payment authorisation.
        JsonNode out = service(new StubDao(creator(BRAND, null, false, null), null, false))
                .clearance(BRAND, CREATOR, new BigDecimal("5000.00"));

        assertThat(out.get("clear").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("over the threshold with no form on the manual rail blocks the payout")
    void manualRailBlocksWithoutForm() {
        JsonNode out = service(new StubDao(creator(BRAND, null, false, null), "700.00", true))
                .clearance(BRAND, CREATOR, BigDecimal.ZERO);

        assertThat(out.get("clear").asBoolean()).isFalse();
        // And it names the form, so "blocked" does not send someone to the logs to find out what
        // to ask for.
        assertThat(out.get("detail").asText()).contains("W-9");
    }

    @Test
    @DisplayName("over the threshold WITH a form on file clears")
    void manualRailClearsWithForm() {
        JsonNode out = service(new StubDao(creator(BRAND, null, false, "2026-01-01T00:00:00Z"), "700.00", true))
                .clearance(BRAND, CREATOR, BigDecimal.ZERO);

        assertThat(out.get("clear").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("where Connect is in use, Stripe answers — not our own column")
    void connectRailDefersToStripe() {
        // Our column says the form is on file; Stripe says payouts are not enabled. Stripe wins,
        // because it collected the form and is the one holding the money.
        JsonNode out = service(new StubDao(creator(BRAND, "acct_1", false, "2026-01-01T00:00:00Z"), "700.00", true))
                .clearance(BRAND, CREATOR, BigDecimal.ZERO);

        assertThat(out.get("clear").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a Connect creator Stripe has enabled clears")
    void connectRailClearsWhenEnabled() {
        JsonNode out = service(new StubDao(creator(BRAND, "acct_1", true, null), "700.00", true))
                .clearance(BRAND, CREATOR, BigDecimal.ZERO);

        assertThat(out.get("clear").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("recording a form for another brand's creator is not found")
    void recordFormRefusesAnotherTenant() {
        assertThatThrownBy(() -> service(new StubDao(creator(OTHER, null, false, null), "0.00", true))
                .recordForm(BRAND, CREATOR, "W-9"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("a brand cannot tick a box that overrides Stripe")
    void recordFormRefusedOnConnect() {
        // Letting them would let a brand pay someone Stripe is holding, which is not a decision
        // this product gets to make.
        assertThatThrownBy(() -> service(new StubDao(creator(BRAND, "acct_1", false, null), "0.00", true))
                .recordForm(BRAND, CREATOR, "W-9"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Stripe");
    }

    @Test
    @DisplayName("an unrecognised form kind is refused rather than stored")
    void recordFormValidatesKind() {
        assertThatThrownBy(() -> service(new StubDao(creator(BRAND, null, false, null), "0.00", true))
                .recordForm(BRAND, CREATOR, "whatever"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("W-9");
    }

    @Test
    @DisplayName("a recorded form is written with the time it arrived")
    void recordFormPersists() {
        StubDao dao = new StubDao(creator(BRAND, null, false, null), "0.00", true);

        service(dao).recordForm(BRAND, CREATOR, "w-8ben");

        assertThat(dao.patches()).hasSize(1);
        assertThat(dao.patches().get(0).get("taxFormKind").asText()).isEqualTo("W-8BEN");
        assertThat(dao.patches().get(0).has("taxFormOnFileAt")).isTrue();
    }
}
