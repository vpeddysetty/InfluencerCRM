package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards what each plan grants, and — more importantly — what an unknown plan grants.
 *
 * <p>{@code accounts.plan} has existed since the Phase 2 tenancy migration and nothing has ever
 * read it: every account has had unlimited everything on every plan. These tests exist because the
 * ways this can go wrong are all silent. An off-by-one grants one extra of everything forever; a
 * fallback to unlimited turns a typo in a plan string into a free upgrade; and none of it surfaces
 * as an error anywhere.
 */
class PlanPolicyTest {

    @Test
    @DisplayName("an unknown plan falls back to free, never to unlimited")
    void unknownPlansFailClosed() {
        // The column is free text with no check constraint, so anything is reachable — a typo, a
        // plan a future billing integration invents, a value from a half-run migration. Every one
        // of those must land on the most restrictive tier. Failing open here would mean a
        // misspelled plan silently grants everything, and nothing would ever report it.
        assertEquals(PlanPolicy.FREE, PlanPolicy.forKey("enterprise"));
        assertEquals(PlanPolicy.FREE, PlanPolicy.forKey("pr0"));
        assertEquals(PlanPolicy.FREE, PlanPolicy.forKey(""));
        assertEquals(PlanPolicy.FREE, PlanPolicy.forKey("   "));
        assertEquals(PlanPolicy.FREE, PlanPolicy.forKey(null));
    }

    @Test
    @DisplayName("plan keys are matched regardless of case or padding")
    void plansNormalize() {
        // No check constraint means "Free" and " pro " are both storable. Matching them strictly
        // would drop such an account to the free tier — for PRO, a paying customer losing what
        // they bought because of whitespace.
        assertEquals(PlanPolicy.FREE, PlanPolicy.forKey("FREE"));
        assertEquals(PlanPolicy.PRO, PlanPolicy.forKey("  Pro "));
        assertEquals(PlanPolicy.AGENCY, PlanPolicy.forKey("AGENCY"));
    }

    @Test
    @DisplayName("an account exactly at its limit is full")
    void limitIsInclusive() {
        // The off-by-one that would grant one extra of everything on every plan, forever, and
        // never show up as an error.
        assertTrue(PlanPolicy.FREE.allows(PlanPolicy.Resource.CREATOR, 24));
        assertFalse(PlanPolicy.FREE.allows(PlanPolicy.Resource.CREATOR, 25));
        assertFalse(PlanPolicy.FREE.allows(PlanPolicy.Resource.CREATOR, 26));
    }

    @Test
    @DisplayName("an account already over its limit is still blocked, not wrapped")
    void overLimitStaysBlocked() {
        // Reachable without any downgrade: a limit introduced above existing accounts, or lowered
        // later. Blocking further creation is correct; what must not happen is the comparison
        // going wrong at large counts and re-opening creation.
        assertFalse(PlanPolicy.FREE.allows(PlanPolicy.Resource.MEMBER, 500));
        assertFalse(PlanPolicy.PRO.allows(PlanPolicy.Resource.CREATOR, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("the agency tier is unlimited on every resource")
    void agencyIsUnlimited() {
        for (PlanPolicy.Resource resource : PlanPolicy.Resource.values()) {
            assertEquals(PlanPolicy.UNLIMITED, PlanPolicy.AGENCY.limitFor(resource));
            assertTrue(PlanPolicy.AGENCY.allows(resource, Long.MAX_VALUE),
                    resource + " must be unlimited on the agency tier");
        }
    }

    @Test
    @DisplayName("multi-brand is what actually separates agency from the rest")
    void onlyAgencyGetsMultipleBrands() {
        // The product's multi-brand tenancy is the agency tier's reason to exist. If a lower tier
        // allowed two brands, the tiers would differ only in size and not in capability.
        assertEquals(1, PlanPolicy.FREE.limitFor(PlanPolicy.Resource.BRAND));
        assertEquals(1, PlanPolicy.PRO.limitFor(PlanPolicy.Resource.BRAND));
        assertFalse(PlanPolicy.FREE.allows(PlanPolicy.Resource.BRAND, 1));
        assertTrue(PlanPolicy.AGENCY.allows(PlanPolicy.Resource.BRAND, 50));
    }

    @Test
    @DisplayName("every paid tier is at least as generous as the one below")
    void tiersAreMonotonic() {
        // An upgrade that reduces an allowance would take away something a customer already had,
        // and the enforcement path would then block them for having paid.
        for (PlanPolicy.Resource resource : PlanPolicy.Resource.values()) {
            assertTrue(atLeast(PlanPolicy.PRO, PlanPolicy.FREE, resource),
                    "pro must not be stingier than free on " + resource);
            assertTrue(atLeast(PlanPolicy.AGENCY, PlanPolicy.PRO, resource),
                    "agency must not be stingier than pro on " + resource);
        }
    }

    private static boolean atLeast(PlanPolicy higher, PlanPolicy lower, PlanPolicy.Resource resource) {
        int high = higher.limitFor(resource);
        int low = lower.limitFor(resource);
        return high == PlanPolicy.UNLIMITED || (low != PlanPolicy.UNLIMITED && high >= low);
    }

    @ParameterizedTest
    @EnumSource(PlanPolicy.Resource.class)
    @DisplayName("zero usage is allowed on every plan and resource")
    void emptyAccountsCanAlwaysCreateTheFirstOne(PlanPolicy.Resource resource) {
        // A limit of 0 anywhere would make a resource unreachable rather than metered — a brand
        // new free account that cannot create its first creator has no way to evaluate anything.
        for (PlanPolicy plan : PlanPolicy.values()) {
            assertTrue(plan.allows(resource, 0),
                    plan + " must allow the first " + resource.singular());
        }
    }

    @Test
    @DisplayName("free limits clear the observed usage they safely can")
    void freeLimitsDoNotStrandMostExistingAccounts() {
        // Measured against the live database on 2026-08-07, per-account maxima: 2 brands,
        // 5 creators, 6 members, 2 landing pages. Enforcement blocks new creation and never
        // deletes, but a limit under what customers already have freezes real accounts on the day
        // it ships, so creators and landing pages were set clear of the observed ceiling.
        assertTrue(PlanPolicy.FREE.allows(PlanPolicy.Resource.CREATOR, 5));
        assertTrue(PlanPolicy.FREE.allows(PlanPolicy.Resource.LANDING_PAGE, 2));

        // Two limits deliberately DO bite on existing accounts, and this records that as a
        // decision rather than an oversight:
        //   - brands: 1 is the whole point of the agency tier. One account has 2.
        //   - members: 1 (was 3). Free is now single-user; one account has 6.
        // Both are frozen at their current size, not reduced — nobody loses a brand or a
        // teammate. If that turns out to be the wrong call, these are the two numbers to move.
        assertFalse(PlanPolicy.FREE.allows(PlanPolicy.Resource.BRAND, 2));
        assertFalse(PlanPolicy.FREE.allows(PlanPolicy.Resource.MEMBER, 6));
    }

    // ---- free is single-user; RBAC is the paid feature ----------------------------------

    @Test
    @DisplayName("free allows exactly one member")
    void freeIsSingleUser() {
        // The product decision: one person runs the free tier end to end. The second person is
        // where a team has adopted the tool, and that is the honest moment to charge.
        assertTrue(PlanPolicy.FREE.allows(PlanPolicy.Resource.MEMBER, 0));
        assertFalse(PlanPolicy.FREE.allows(PlanPolicy.Resource.MEMBER, 1));
        assertEquals(1, PlanPolicy.FREE.limitFor(PlanPolicy.Resource.MEMBER));
    }

    @Test
    @DisplayName("free still holds a real roster and its brand workspace")
    void freeRemainsUsableAlone() {
        // Reducing seats must not reduce the thing being evaluated. The wedge is spreadsheet
        // replacement, and a roster too small to hold a real creator list makes the product
        // unevaluable — which loses the trial rather than converting it.
        assertTrue(PlanPolicy.FREE.allows(PlanPolicy.Resource.CREATOR, 24));
        assertTrue(PlanPolicy.FREE.allows(PlanPolicy.Resource.BRAND, 0));
        assertEquals(25, PlanPolicy.FREE.limitFor(PlanPolicy.Resource.CREATOR));
    }

    @Test
    @DisplayName("role-based access is paid")
    void rolesArePaid() {
        assertFalse(PlanPolicy.FREE.allowsRoleBasedAccess());
        assertTrue(PlanPolicy.PRO.allowsRoleBasedAccess());
        assertTrue(PlanPolicy.AGENCY.allowsRoleBasedAccess());
    }

    @Test
    @DisplayName("an unknown plan gets neither seats nor roles")
    void unknownPlansFailClosedOnRoles() {
        // Same instinct as every other limit: a typo must not become a free upgrade. Worth its
        // own assertion because a capability flag is easier to accidentally default to true than
        // a number is to default to unlimited.
        assertFalse(PlanPolicy.forKey("enterprise-typo").allowsRoleBasedAccess());
        assertFalse(PlanPolicy.forKey(null).allowsRoleBasedAccess());
        assertFalse(PlanPolicy.forKey("").allowsRoleBasedAccess());
    }
}
