package com.influencer.dao.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signed tenant beats the requested one.
 *
 * <p>The hole: DAO controllers take {@code brandId} as an OPTIONAL query parameter and fall back to
 * an unfiltered query. So {@code GET /creators} returned the whole table across every tenant, and
 * naming someone else's brand returned theirs — nothing had to be forged, the parameter simply had
 * to be changed or omitted.
 */
class CallerTenantTest {

    private static final String SIGNED = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";

    @AfterEach
    void clearContext() {
        // Threads are pooled; a leaked tenant would make the next test read as the previous one.
        MDC.clear();
    }

    @Test
    @DisplayName("a caller asking for another tenant gets its own")
    void signedWins() {
        MDC.put("tenant", SIGNED);

        // The attack, in one line: the request names someone else's brand and the token does not.
        assertEquals(SIGNED, CallerTenant.resolve(OTHER));
    }

    @Test
    @DisplayName("omitting brandId no longer means 'every tenant'")
    void omittingTheParameterStillScopes() {
        MDC.put("tenant", SIGNED);

        // Previously null here reached the controller and selected findAll().
        assertEquals(SIGNED, CallerTenant.resolve(null));
    }

    @Test
    @DisplayName("a matching request is unchanged")
    void matchingPassesThrough() {
        MDC.put("tenant", SIGNED);
        assertEquals(SIGNED, CallerTenant.resolve(SIGNED));
    }

    @Test
    @DisplayName("without a signed tenant the requested value is used, so migration does not break")
    void fallsBackDuringMigration() {
        // A legacy token carries no tid. Refusing here would take the platform down rather than
        // secure it; the fallback is logged instead, and those logs are what say when enforcement
        // can be turned on.
        assertEquals(OTHER, CallerTenant.resolve(OTHER));
        assertNull(CallerTenant.resolve(null));
        assertNull(CallerTenant.signed());
    }

    @Test
    @DisplayName("a blank signed tenant counts as absent")
    void blankIsAbsent() {
        MDC.put("tenant", "   ");
        assertNull(CallerTenant.signed());
        assertEquals(OTHER, CallerTenant.resolve(OTHER));
    }

    @Test
    @DisplayName("requireMatch refuses a mismatch but tolerates an unsigned call")
    void requireMatchIsStagedForNow() {
        MDC.put("tenant", SIGNED);
        assertTrue(CallerTenant.requireMatch(SIGNED));
        assertFalse(CallerTenant.requireMatch(OTHER));

        MDC.clear();
        // The switch that completes this work is making THIS return false.
        assertTrue(CallerTenant.requireMatch(OTHER));
    }
}
