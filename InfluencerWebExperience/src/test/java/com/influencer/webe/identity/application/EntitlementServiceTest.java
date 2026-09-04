package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import com.influencer.platform.workload.WorkloadTokenIssuer;
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
            new WorkloadTokenIssuer("test", "", ""));
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

    /** As {@link #serviceOnPlan}, with the default plan a deployment would configure. */
    private static EntitlementService serviceOnPlan(String plan, String defaultPlan) {
        return new EntitlementService(new StubDao() {
            @Override
            public JsonNode get(String path, Map<String, String> query) {
                return plan == null
                        ? MAPPER.createObjectNode()
                        : MAPPER.createObjectNode().put("plan", plan);
            }
        }, defaultPlan);
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

    // ---------------------------------------------------------------- batch capacity

    @Test
    @DisplayName("a batch that exactly fills the plan is allowed")
    void aBatchMayLandExactlyOnTheLimit() {
        // The off-by-one that matters most here. Pro allows 10 members; 3 committed plus a batch of
        // 7 is exactly 10, and refusing it would reject the batch an admin sized on purpose after
        // reading "7 seats available" on the same screen.
        assertDoesNotThrow(() -> serviceOnPlan("pro")
                .requireCapacityFor(ACCOUNT, PlanPolicy.Resource.MEMBER, 3, 7));
    }

    @Test
    @DisplayName("a batch one row past the limit is refused whole")
    void oneRowTooManyRefusesTheWholeBatch() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> serviceOnPlan("pro").requireCapacityFor(ACCOUNT, PlanPolicy.Resource.MEMBER, 3, 8));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, error.getStatusCode());
    }

    @Test
    @DisplayName("the batch refusal names what was asked, what is available, and that nothing was sent")
    void theBatchMessageIsActionable() {
        // An admin who uploaded 8 names needs three facts to act: how many they asked for, how many
        // they can have, and whether some of them went out anyway. The last is the one that decides
        // whether their next move is to fix the file or to go hunting through the members list.
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> serviceOnPlan("pro").requireCapacityFor(ACCOUNT, PlanPolicy.Resource.MEMBER, 8, 5));

        String reason = error.getReason();
        assertTrue(reason.contains("5"), "it must name what was requested: " + reason);
        assertTrue(reason.contains("2"), "it must name what is available: " + reason);
        assertTrue(reason.contains("pro"), reason);
        assertTrue(reason.contains("team members"), reason);
        assertTrue(reason.contains("Nothing was sent"),
                "it must say nothing was created: " + reason);
        assertTrue(reason.contains("Agency"), "it must name the tier that fixes it: " + reason);
    }

    @Test
    @DisplayName("an over-limit account is told it has zero available, never a negative number")
    void availableNeverGoesNegative() {
        // Reachable today: the free member limit dropped from 3 to 1 with accounts already above it.
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> serviceOnPlan("free").requireCapacityFor(ACCOUNT, PlanPolicy.Resource.MEMBER, 6, 1));

        assertTrue(error.getReason().contains("0 available"),
                "an account over its limit has zero seats, not minus five: " + error.getReason());
    }

    @Test
    @DisplayName("a batch that creates nothing is not refused for capacity")
    void zeroAdditionalIsAlwaysAllowed() {
        // Every row was a duplicate or an existing member, so nothing needs a seat. Reporting a
        // limit problem for a request that consumes no capacity would be a lie.
        assertDoesNotThrow(() -> serviceOnPlan("free")
                .requireCapacityFor(ACCOUNT, PlanPolicy.Resource.MEMBER, 99, 0));
    }

    @Test
    @DisplayName("the agency tier is never refused a batch, however large")
    void agencyIsNeverBlockedInBatch() {
        assertDoesNotThrow(() -> serviceOnPlan("agency")
                .requireCapacityFor(ACCOUNT, PlanPolicy.Resource.MEMBER, 10_000, 500));
    }

    @Test
    @DisplayName("an unreadable plan refuses the batch on free limits rather than letting it through")
    void batchFailsClosedWhenTheDaoIsDown() {
        assertThrows(ResponseStatusException.class,
                () -> serviceThatCannotRead().requireCapacityFor(ACCOUNT, PlanPolicy.Resource.MEMBER, 0, 5));
    }

    @Test
    @DisplayName("remaining capacity is what a UI can size a batch against")
    void remainingCapacityIsReportable() {
        assertEquals(7, serviceOnPlan("pro").remainingCapacity(ACCOUNT, PlanPolicy.Resource.MEMBER, 3));
        assertEquals(0, serviceOnPlan("pro").remainingCapacity(ACCOUNT, PlanPolicy.Resource.MEMBER, 10));
        // Over the limit is zero remaining, not a negative allowance.
        assertEquals(0, serviceOnPlan("free").remainingCapacity(ACCOUNT, PlanPolicy.Resource.MEMBER, 6));
        // Unlimited reports the sentinel the UI already understands rather than a large number that
        // would render as a cap.
        assertEquals(PlanPolicy.UNLIMITED,
                serviceOnPlan("agency").remainingCapacity(ACCOUNT, PlanPolicy.Resource.MEMBER, 10_000));
    }

    @Test
    @DisplayName("the configured default applies to an account with no plan of its own")
    void defaultPlanIsConfigurable() {
        // Case-study period (2026-09): a prospect evaluating the product must not meet a
        // 25-creator wall with no pricing page to explain it and no way to upgrade. The tiers
        // themselves are untouched -- only which one an unset account gets.
        EntitlementService service = serviceOnPlan(null, "agency");

        assertEquals(PlanPolicy.AGENCY, service.planFor(ACCOUNT));
    }

    @Test
    @DisplayName("an explicit plan still wins over the default")
    void explicitPlanBeatsTheDefault() {
        // The default is for accounts that have no plan. One that does is unaffected, which is what
        // makes this reversible: set the property back and nothing else has to be undone.
        EntitlementService service = serviceOnPlan("free", "agency");

        assertEquals(PlanPolicy.FREE, service.planFor(ACCOUNT));
    }

    @Test
    @DisplayName("an unrecognised STORED plan still fails closed to free, whatever the default")
    void typosStillFailClosed() {
        // The distinction worth keeping: a deliberate default is configurable, a typo is not. An
        // unmigrated or misspelled value must never become a grant of everything.
        EntitlementService service = serviceOnPlan("freee", "agency");

        assertEquals(PlanPolicy.FREE, service.planFor(ACCOUNT));
    }

    @Test
    @DisplayName("exactly one constructor is the injectable one, so Spring can start")
    void springCanChooseAConstructor() {
        // THE BUG THIS EXISTS FOR. Adding the test-only overload below gave this class two
        // constructors with no @Autowired. Spring then looks for a no-arg constructor, finds none,
        // and the entire BFF fails to start -- the API was down for ten minutes on the v1.0.58 roll
        // while every unit test passed, because unit tests call constructors directly and never ask
        // the container to choose.
        //
        // Asserted structurally rather than by booting a context: this module's WebMvcTest recipe
        // needs two filters excluded and Mockito does not run on this JDK, so a full-context test
        // here would be more machinery than the fact deserves.
        long injectable = java.util.Arrays.stream(EntitlementService.class.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(
                        org.springframework.beans.factory.annotation.Autowired.class))
                .count();

        assertEquals(1, injectable,
                "with more than one constructor, exactly one must carry @Autowired or Spring cannot start");
    }
}
