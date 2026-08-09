package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import com.influencer.webe.shared.workload.WorkloadTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards how a plan is resolved and how a limit is refused.
 *
 * <p>The DAO client is subclassed rather than mocked with a framework — one method needs
 * overriding, and the interesting cases are "what does it do when this read fails" and "what does
 * the user see", neither of which needs a mocking library to express.
 */
class EntitlementServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ACCOUNT = UUID.randomUUID();

    /**
     * A DAO stub. Subclassed rather than mocked, matching {@code AnalyticsWindowTest}: Mockito's
     * bundled bytecode engine cannot mock this class under Java 26, and the superclass constructor
     * calls {@code factory.create()}, so the factory is overridden to build no real TLS client.
     * Nothing here opens a socket.
     */
    private abstract static class StubDao extends DaoGatewayClient {
        StubDao() {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            },
            // No signing key: the stub issues no workload token, which is the correct shape for a
            // test that never reaches the network.
            new WorkloadTokenIssuer("", "test"));
        }
    }

    /** A gateway that answers the account lookup with {@code plan} and fails nothing else. */
    private static EntitlementService serviceOnPlan(String plan) {
        return new EntitlementService(new StubDao() {
            @Override
            public JsonNode get(String path, Map<String, String> query) {
                return MAPPER.createObjectNode().put("plan", plan);
            }
        });
    }

    /** A gateway whose every read blows up — a DAO outage. */
    private static EntitlementService serviceThatCannotRead() {
        return new EntitlementService(new StubDao() {
            @Override
            public JsonNode get(String path, Map<String, String> query) {
                throw new IllegalStateException("DAO unreachable");
            }
        });
    }

    @Test
    @DisplayName("the plan is read from the account, not assumed")
    void resolvesThePlan() {
        assertEquals(PlanPolicy.PRO, serviceOnPlan("pro").planFor(ACCOUNT));
        assertEquals(PlanPolicy.AGENCY, serviceOnPlan("agency").planFor(ACCOUNT));
    }

    @Test
    @DisplayName("an unreadable plan falls back to free rather than unlimited")
    void failsClosedWhenTheDaoIsDown() {
        // An outage must not become a way past the limits. Free rather than "deny everything",
        // because an account within its limits should still work while the DAO is recovering.
        assertEquals(PlanPolicy.FREE, serviceThatCannotRead().planFor(ACCOUNT));
        assertEquals(PlanPolicy.FREE, serviceOnPlan(null).planFor(ACCOUNT));
        assertEquals(PlanPolicy.FREE, serviceOnPlan("nonsense").planFor(ACCOUNT));
    }

    @Test
    @DisplayName("a null account gets the free tier, never a free pass")
    void nullAccountIsNotUnlimited() {
        // accountId comes from a verified JWT claim, so null should be unreachable — but the
        // wrong answer to an impossible input is "unlimited", and that is worth pinning down.
        assertEquals(PlanPolicy.FREE, serviceOnPlan("agency").planFor(null));
    }

    @Test
    @DisplayName("within the limit, creation is allowed")
    void allowsWithinTheLimit() {
        assertDoesNotThrow(() -> serviceOnPlan("free")
                .requireCapacity(ACCOUNT, PlanPolicy.Resource.CREATOR, 24));
    }

    @Test
    @DisplayName("at the limit, creation is refused with 402 rather than 403")
    void refusesWithPaymentRequired() {
        // 402, not 403: the caller is authorized, their plan simply does not include this. A 403
        // tells a UI to hide the action; 402 tells it to offer the upgrade, which is the remedy.
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> serviceOnPlan("free").requireCapacity(ACCOUNT, PlanPolicy.Resource.CREATOR, 25));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, error.getStatusCode());
    }

    @Test
    @DisplayName("the refusal names the limit, the plan, and what happens to existing data")
    void theMessageIsActionable() {
        // "Limit reached" alone forces a support ticket to find out which limit and what the next
        // tier gives. And a user hitting a cap needs to know their data is safe: the fear is that
        // being over the limit means something gets deleted.
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> serviceOnPlan("free").requireCapacity(ACCOUNT, PlanPolicy.Resource.CREATOR, 25));

        String reason = error.getReason();
        assertTrue(reason.contains("free"), reason);
        assertTrue(reason.contains("25"), reason);
        assertTrue(reason.contains("creators"), reason);
        assertTrue(reason.contains("Pro"), "it must name the tier that fixes it: " + reason);
        assertTrue(reason.contains("nothing you already have"),
                "it must say existing data is untouched: " + reason);
    }

    @Test
    @DisplayName("the agency tier is never refused")
    void agencyIsNeverBlocked() {
        for (PlanPolicy.Resource resource : PlanPolicy.Resource.values()) {
            assertDoesNotThrow(() -> serviceOnPlan("agency")
                    .requireCapacity(ACCOUNT, resource, 10_000));
        }
    }

    @Test
    @DisplayName("a pro account is pointed at agency, not back at pro")
    void theUpgradePathPointsUpward() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> serviceOnPlan("pro").requireCapacity(ACCOUNT, PlanPolicy.Resource.BRAND, 1));

        assertTrue(error.getReason().contains("Agency"), error.getReason());
    }
}
