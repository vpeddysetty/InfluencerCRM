package com.influencer.webe.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.payout.PayoutProvider;
import com.influencer.webe.payout.PayoutProviderRegistry;
import com.influencer.webe.payout.TaxThresholdService;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The smallest payout worth sending (roadmap PR-56).
 *
 * <p>The behaviour that matters is not that small payouts are refused — it is that <b>nothing is
 * lost by the refusal</b>. The commissions stay {@code approved} and roll into the next run, which
 * is what makes the floor a delay rather than a deduction. A floor that forfeited the balance would
 * be taking money off a creator for having a quiet month, and the two are indistinguishable from
 * the outside unless the ledger is left alone.
 */
class MinimumPayoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();

    /** Answers the commission list, and records every write so a test can prove none happened. */
    private static final class StubDao extends DaoGatewayClient {
        private final String commissionAmount;
        final List<String> writes = new ArrayList<>();

        StubDao(String commissionAmount) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    return null;
                }
            }, null);
            this.commissionAmount = commissionAmount;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.startsWith("/influencer-commissions")) {
                ArrayNode rows = MAPPER.createArrayNode();
                ObjectNode row = rows.addObject();
                row.put("id", UUID.randomUUID().toString());
                row.put("creatorId", CREATOR.toString());
                row.put("commissionAmount", commissionAmount);
                row.put("currency", "USD");
                row.put("status", "approved");
                return rows;
            }
            return null;
        }

        @Override
        public JsonNode post(String path, JsonNode payload) {
            writes.add("POST " + path);
            return MAPPER.createObjectNode().put("id", UUID.randomUUID().toString());
        }

        @Override
        public JsonNode put(String path, JsonNode payload) {
            writes.add("PUT " + path);
            return payload;
        }
    }

    /** Clears everything: this test is about the floor, not the tax gate. */
    private static final class ClearTax extends TaxThresholdService {
        ClearTax() {
            super(null, new ResponseShapeService(MAPPER));
        }

        @Override
        public JsonNode clearance(UUID brandId, UUID creatorId, java.math.BigDecimal amount) {
            return MAPPER.createObjectNode().put("clear", true);
        }
    }

    /**
     * A provider that settles anything.
     *
     * <p>Needed because the provider lookup runs BEFORE the floor: with an empty registry every
     * case here would fail on "unknown provider" and the test would pass while proving nothing
     * about the minimum. Its presence is what makes the floor the reason a payout stops.
     */
    private static final class AlwaysPays implements PayoutProvider {
        @Override public String key() { return "manual"; }
        @Override public String displayName() { return "Manual"; }
        @Override public PayoutResult pay(String payoutId, String creatorId,
                                          java.math.BigDecimal amount, String currency, String note) {
            return PayoutResult.paid("ref-" + payoutId);
        }
    }

    private PayoutService service(StubDao dao, String minimum) {
        return new PayoutService(dao, new ResponseShapeService(MAPPER),
                new PayoutProviderRegistry(List.<PayoutProvider>of(new AlwaysPays())), new ClearTax(),
                minimum, "Monthly, net 30");
    }

    @Test
    @DisplayName("a balance under the floor is refused, and NOTHING is written")
    void underTheFloorWritesNothing() {
        // The whole justification for the floor. If a refused run still flipped commissions or left
        // a payout row behind, the balance would be stranded rather than rolled forward -- and the
        // creator would have been charged for a quiet month.
        StubDao dao = new StubDao("12.00");

        assertThrows(ResponseStatusException.class,
                () -> service(dao, "50.00").createPayout(BRAND, CREATOR, "manual"));

        assertTrue(dao.writes.isEmpty(),
                "a refused payout must leave the ledger untouched, wrote: " + dao.writes);
    }

    @Test
    @DisplayName("the refusal names both figures, so a brand knows to wait rather than hunt a setting")
    void theMessageIsActionable() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(new StubDao("12.00"), "50.00").createPayout(BRAND, CREATOR, "manual"));

        String message = String.valueOf(thrown.getReason());
        assertTrue(message.contains("12.00"), "the outstanding balance must be named: " + message);
        assertTrue(message.contains("50.00"), "the minimum must be named: " + message);
        assertTrue(message.contains("rolls into the next run"),
                "it must say the balance is kept, not lost: " + message);
    }

    @Test
    @DisplayName("exactly the minimum is payable — the bound is inclusive")
    void exactlyTheMinimumIsPayable() {
        // The case a user is most likely to test, and the one an exclusive bound gets wrong.
        StubDao dao = new StubDao("50.00");

        service(dao, "50.00").createPayout(BRAND, CREATOR, "manual");

        assertTrue(dao.writes.stream().anyMatch(w -> w.startsWith("POST /influencer-payouts")),
                "50.00 must clear a 50.00 floor and write a payout, wrote: " + dao.writes);
    }

    @Test
    @DisplayName("the floor can be configured away for a rail that costs nothing to send")
    void aZeroFloorDisablesIt() {
        StubDao dao = new StubDao("0.01");

        service(dao, "0").createPayout(BRAND, CREATOR, "manual");

        assertTrue(dao.writes.stream().anyMatch(w -> w.startsWith("POST /influencer-payouts")),
                "a zero floor must let a penny through, wrote: " + dao.writes);
    }

    @Test
    @DisplayName("the terms are readable before anyone tries to pay")
    void termsAreStatedUpFront() {
        // A minimum discovered only as a 409 at the moment of paying is PR-49's mistake in another
        // costume: the rule is fine, learning it at the worst moment is not.
        JsonNode terms = service(new StubDao("0"), "50.00").payoutTerms();

        assertEquals("50.00", terms.get("minimumAmount").asText());
        assertEquals("Monthly, net 30", terms.get("schedule").asText());
        // Said out loud: nothing runs payouts on a timer, and implying a scheduler this product
        // does not have would be worse than stating a sentence a brand configured.
        assertEquals(false, terms.get("scheduleEnforced").asBoolean());
    }
}
