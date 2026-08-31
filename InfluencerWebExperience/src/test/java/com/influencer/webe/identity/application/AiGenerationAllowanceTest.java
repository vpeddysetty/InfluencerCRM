package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monthly AI allowance (V48).
 *
 * <p>Tests the POLICY, not the plumbing: {@code allowsAiGeneration} takes the count so far and
 * decides, which is what keeps the rule testable without a database. The counting query itself is
 * verified against real Postgres, where a JPA test would only re-assert the mapping.
 */
class AiGenerationAllowanceTest {

    @Test
    @DisplayName("the free tier stops at its allowance and not before")
    void freeTierStopsAtTwenty() {
        PlanPolicy free = PlanPolicy.FREE;
        assertEquals(20, free.maxAiGenerationsPerMonth());

        assertTrue(free.allowsAiGeneration(0), "a first generation");
        assertTrue(free.allowsAiGeneration(19), "the twentieth");
        // Twenty USED means twenty made, so the twenty-first is the one refused.
        assertFalse(free.allowsAiGeneration(20), "the twenty-first");
        assertFalse(free.allowsAiGeneration(500));
    }

    @Test
    @DisplayName("paid plans get more, and the agency tier is uncapped")
    void paidPlansGetMore() {
        assertTrue(PlanPolicy.PRO.allowsAiGeneration(100));
        assertFalse(PlanPolicy.PRO.allowsAiGeneration(500));

        // UNLIMITED is -1, and a naive `used < limit` would refuse everything on a negative limit.
        assertTrue(PlanPolicy.AGENCY.allowsAiGeneration(0));
        assertTrue(PlanPolicy.AGENCY.allowsAiGeneration(10_000));
    }

    @Test
    @DisplayName("an unknown plan gets the free allowance, never an unlimited one")
    void unknownPlanIsFree() {
        // Mirrors forKey's existing contract: a typo in a plan name must not become a way to
        // bypass the ceiling. Asserted here because this allowance is about spend, where failing
        // open costs real money rather than merely permitting an extra row.
        assertEquals(PlanPolicy.FREE.maxAiGenerationsPerMonth(),
                PlanPolicy.forKey("enterprise-typo").maxAiGenerationsPerMonth());
        assertEquals(PlanPolicy.FREE.maxAiGenerationsPerMonth(),
                PlanPolicy.forKey(null).maxAiGenerationsPerMonth());
    }

    @Test
    @DisplayName("the capacity limits are untouched by the new field")
    void capacityLimitsUnchanged() {
        // The constructor gained an argument; every existing limit has to still read the same.
        // A shifted positional parameter would be invisible otherwise -- the code compiles either
        // way and the numbers are all small integers.
        assertEquals(1, PlanPolicy.FREE.limitFor(PlanPolicy.Resource.BRAND));
        assertEquals(25, PlanPolicy.FREE.limitFor(PlanPolicy.Resource.CREATOR));
        assertEquals(1, PlanPolicy.FREE.limitFor(PlanPolicy.Resource.MEMBER));
        assertEquals(3, PlanPolicy.FREE.limitFor(PlanPolicy.Resource.LANDING_PAGE));
        assertEquals(2, PlanPolicy.FREE.limitFor(PlanPolicy.Resource.SAVED_TEMPLATE));

        assertEquals(250, PlanPolicy.PRO.limitFor(PlanPolicy.Resource.CREATOR));
        assertEquals(10, PlanPolicy.PRO.limitFor(PlanPolicy.Resource.MEMBER));
        assertEquals(PlanPolicy.UNLIMITED, PlanPolicy.AGENCY.limitFor(PlanPolicy.Resource.CREATOR));
    }
}
