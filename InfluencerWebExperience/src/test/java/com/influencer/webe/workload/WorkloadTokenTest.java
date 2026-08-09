package com.influencer.webe.workload;

import com.influencer.webe.shared.workload.WorkloadToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service-to-service credential (roadmap step 3).
 *
 * <p>Each test here corresponds to one property the static {@code X-Service-Token} lacked. They are
 * written as the attack the property prevents, because that is what would otherwise be
 * rediscovered later as a finding rather than a decision.
 */
class WorkloadTokenTest {

    private static final String KEY = "a-signing-key-long-enough-to-be-a-real-hmac-secret";
    private static final String OTHER_KEY = "a-different-signing-key-entirely-for-this-test-x";
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    private String issue(String audience, String tenant) {
        return WorkloadToken.issue("web-experience", audience,
                Set.of("dao:read", "dao:write"), tenant, "req-abc123", KEY, NOW);
    }

    @Test
    @DisplayName("a token round-trips with every claim intact")
    void roundTrips() {
        WorkloadToken.Claims claims =
                WorkloadToken.verify(issue("dao", "tenant-1"), "dao", KEY, NOW);

        assertNotNull(claims);
        assertEquals("web-experience", claims.issuer());
        assertEquals("dao", claims.audience());
        assertEquals("tenant-1", claims.tenantId());
        assertEquals("req-abc123", claims.requestId());
        assertTrue(claims.hasScope("dao:read"));
    }

    @Test
    @DisplayName("a token for one service is refused by another")
    void audienceIsBinding() {
        // Without this a token captured on the DAO path is replayable against the BFF. The static
        // shared secret had no audience at all, so one capture worked everywhere it was trusted.
        String forDao = issue("dao", "tenant-1");

        assertNotNull(WorkloadToken.verify(forDao, "dao", KEY, NOW));
        assertNull(WorkloadToken.verify(forDao, "bff", KEY, NOW));
    }

    @Test
    @DisplayName("a token is dead once it expires")
    void expires() {
        String token = issue("dao", "tenant-1");

        assertNotNull(WorkloadToken.verify(token, "dao", KEY, NOW.plusSeconds(60)));
        // TTL is 5 minutes plus 30s skew; 10 minutes is unambiguously past it.
        assertNull(WorkloadToken.verify(token, "dao", KEY, NOW.plusSeconds(600)));
    }

    @Test
    @DisplayName("clock skew within tolerance still verifies")
    void toleratesSkew() {
        // Hosts disagree by seconds. Without tolerance this would produce intermittent 401s that
        // look like a bug in whatever called at the wrong moment.
        String token = issue("dao", "tenant-1");
        assertNotNull(WorkloadToken.verify(token, "dao", KEY, NOW.plusSeconds(310)));
    }

    @Test
    @DisplayName("a token signed with another key is refused")
    void rejectsAForeignSignature() {
        assertNull(WorkloadToken.verify(issue("dao", "tenant-1"), "dao", OTHER_KEY, NOW));
    }

    @Test
    @DisplayName("altering any part of the token invalidates it")
    void detectsTampering() {
        String token = issue("dao", "tenant-1");
        String[] parts = token.split("\\.");

        // Swap the payload for one claiming a different tenant, keeping the original signature.
        // This is the attack the signature exists to stop: reading another tenant's data by
        // editing the credential rather than the request.
        String forged = WorkloadToken.issue("web-experience", "dao",
                Set.of("dao:read"), "tenant-2", "req-abc123", OTHER_KEY, NOW).split("\\.")[1];

        assertNull(WorkloadToken.verify(parts[0] + "." + forged + "." + parts[2], "dao", KEY, NOW));
    }

    @Test
    @DisplayName("the tenant travels signed, so it cannot be chosen by the caller")
    void tenantIsSigned() {
        // The DAO takes brandId as an OPTIONAL query parameter, so omitting it returns every
        // tenant's rows. A signed tid lets the DAO stop trusting that parameter.
        WorkloadToken.Claims claims =
                WorkloadToken.verify(issue("dao", "brand-42"), "dao", KEY, NOW);

        assertEquals("brand-42", claims.tenantId());
    }

    @Test
    @DisplayName("a malformed or empty token is refused rather than throwing")
    void handlesGarbage() {
        // These arrive from the network. Throwing would turn a bad request into a 500 and hand a
        // prober a way to distinguish failure modes.
        assertNull(WorkloadToken.verify(null, "dao", KEY, NOW));
        assertNull(WorkloadToken.verify("", "dao", KEY, NOW));
        assertNull(WorkloadToken.verify("not-a-token", "dao", KEY, NOW));
        assertNull(WorkloadToken.verify("v1.only-two", "dao", KEY, NOW));
        assertNull(WorkloadToken.verify("v9.aaa.bbb", "dao", KEY, NOW));
        assertNull(WorkloadToken.verify("v1.!!!invalid-base64!!!.sig", "dao", KEY, NOW));
    }

    @Test
    @DisplayName("no key configured verifies nothing")
    void failsClosedWithoutAKey() {
        // An unconfigured verifier must not accept everything — that would be the shared-secret
        // failure mode with extra steps.
        assertNull(WorkloadToken.verify(issue("dao", "t"), "dao", "", NOW));
        assertNull(WorkloadToken.verify(issue("dao", "t"), "dao", null, NOW));
    }

    @Test
    @DisplayName("the issuer is carried, so audit lines name the real caller")
    void carriesTheIssuer() {
        // The DAO filter previously hard-coded the principal as "web-experience" no matter who
        // called, which made its audit trail a constant.
        String fromWorkflow = WorkloadToken.issue("workflow-service", "dao",
                Set.of("dao:read"), "t", "req-1", KEY, NOW);

        assertEquals("workflow-service",
                WorkloadToken.verify(fromWorkflow, "dao", KEY, NOW).issuer());
    }

    @Test
    @DisplayName("a token with no tenant reports null rather than an empty string")
    void handlesAbsentTenant() {
        // Not every internal call is tenant-scoped. The distinction has to survive the encoding,
        // or a receiver cannot tell "no tenant" from "the tenant named empty string".
        WorkloadToken.Claims claims =
                WorkloadToken.verify(issue("dao", null), "dao", KEY, NOW);

        assertNull(claims.tenantId());
        assertFalse(claims.scope().isEmpty());
    }
}
