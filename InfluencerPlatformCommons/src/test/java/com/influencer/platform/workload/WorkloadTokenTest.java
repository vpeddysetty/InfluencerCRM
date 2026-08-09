package com.influencer.platform.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                WorkloadToken.verify(issue("dao", "tenant-1"), "dao", null, KEY, NOW);

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

        assertNotNull(WorkloadToken.verify(forDao, "dao", null, KEY, NOW));
        assertNull(WorkloadToken.verify(forDao, "bff", null, KEY, NOW));
    }

    @Test
    @DisplayName("a token is dead once it expires")
    void expires() {
        String token = issue("dao", "tenant-1");

        assertNotNull(WorkloadToken.verify(token, "dao", null, KEY, NOW.plusSeconds(60)));
        // TTL is 5 minutes plus 30s skew; 10 minutes is unambiguously past it.
        assertNull(WorkloadToken.verify(token, "dao", null, KEY, NOW.plusSeconds(600)));
    }

    @Test
    @DisplayName("clock skew within tolerance still verifies")
    void toleratesSkew() {
        // Hosts disagree by seconds. Without tolerance this would produce intermittent 401s that
        // look like a bug in whatever called at the wrong moment.
        String token = issue("dao", "tenant-1");
        assertNotNull(WorkloadToken.verify(token, "dao", null, KEY, NOW.plusSeconds(310)));
    }

    @Test
    @DisplayName("a token signed with another key is refused")
    void rejectsAForeignSignature() {
        assertNull(WorkloadToken.verify(issue("dao", "tenant-1"), "dao", null, OTHER_KEY, NOW));
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

        assertNull(WorkloadToken.verify(parts[0] + "." + forged + "." + parts[2], "dao", null, KEY, NOW));
    }

    @Test
    @DisplayName("the tenant travels signed, so it cannot be chosen by the caller")
    void tenantIsSigned() {
        // The DAO takes brandId as an OPTIONAL query parameter, so omitting it returns every
        // tenant's rows. A signed tid lets the DAO stop trusting that parameter.
        WorkloadToken.Claims claims =
                WorkloadToken.verify(issue("dao", "brand-42"), "dao", null, KEY, NOW);

        assertEquals("brand-42", claims.tenantId());
    }

    @Test
    @DisplayName("a malformed or empty token is refused rather than throwing")
    void handlesGarbage() {
        // These arrive from the network. Throwing would turn a bad request into a 500 and hand a
        // prober a way to distinguish failure modes.
        assertNull(WorkloadToken.verify(null, "dao", null, KEY, NOW));
        assertNull(WorkloadToken.verify("", "dao", null, KEY, NOW));
        assertNull(WorkloadToken.verify("not-a-token", "dao", null, KEY, NOW));
        assertNull(WorkloadToken.verify("v1.only-two", "dao", null, KEY, NOW));
        assertNull(WorkloadToken.verify("v9.aaa.bbb", "dao", null, KEY, NOW));
        assertNull(WorkloadToken.verify("v1.!!!invalid-base64!!!.sig", "dao", null, KEY, NOW));
    }

    @Test
    @DisplayName("no key configured verifies nothing")
    void failsClosedWithoutAKey() {
        // An unconfigured verifier must not accept everything — that would be the shared-secret
        // failure mode with extra steps.
        assertNull(WorkloadToken.verify(issue("dao", "t"), "dao", null, "", NOW));
        assertNull(WorkloadToken.verify(issue("dao", "t"), "dao", null, null, NOW));
    }

    @Test
    @DisplayName("the issuer is carried, so audit lines name the real caller")
    void carriesTheIssuer() {
        // The DAO filter previously hard-coded the principal as "web-experience" no matter who
        // called, which made its audit trail a constant.
        String fromWorkflow = WorkloadToken.issue("workflow-service", "dao",
                Set.of("dao:read"), "t", "req-1", KEY, NOW);

        assertEquals("workflow-service",
                WorkloadToken.verify(fromWorkflow, "dao", null, KEY, NOW).issuer());
    }

    @Test
    @DisplayName("a token with no tenant reports null rather than an empty string")
    void handlesAbsentTenant() {
        // Not every internal call is tenant-scoped. The distinction has to survive the encoding,
        // or a receiver cannot tell "no tenant" from "the tenant named empty string".
        WorkloadToken.Claims claims =
                WorkloadToken.verify(issue("dao", null), "dao", null, KEY, NOW);

        assertNull(claims.tenantId());
        assertFalse(claims.scope().isEmpty());
    }

    // ---- v2: asymmetric, and why it matters ---------------------------------------------

    private static final WorkloadKeyPairGenerator.Pair BFF_KEYS = WorkloadKeyPairGenerator.generate();
    private static final WorkloadKeyPairGenerator.Pair OTHER_KEYS = WorkloadKeyPairGenerator.generate();

    private String issueSigned(String audience, String tenant) {
        return WorkloadToken.issueSigned("web-experience", audience,
                Set.of("dao:read"), tenant, "req-signed-1", BFF_KEYS.privateKey(), NOW);
    }

    @Test
    @DisplayName("an Ed25519 token round-trips against the matching public key")
    void signedRoundTrips() {
        WorkloadToken.Claims claims = WorkloadToken.verifySigned(
                issueSigned("dao", "brand-42"), "dao", BFF_KEYS.publicKey(), NOW);

        assertNotNull(claims);
        assertEquals("web-experience", claims.issuer());
        assertEquals("brand-42", claims.tenantId());
        assertTrue(claims.isAsymmetric());
    }

    @Test
    @DisplayName("THE POINT: a verifier holding only the public key cannot mint")
    void thePublicKeyCannotSign() {
        // This is the whole reason for the asymmetric scheme. Under HMAC, the DAO's config
        // contains the same secret the BFF signs with — so anyone who reads the DAO's config can
        // mint tokens claiming to be the BFF, for any tenant. Here, signing with the public key is
        // not merely disallowed, it is not expressible: signSigned rejects it outright.
        assertThrows(IllegalStateException.class, () -> WorkloadToken.issueSigned(
                "web-experience", "dao", Set.of("dao:read"), "t", "r",
                BFF_KEYS.publicKey(), NOW));
    }

    @Test
    @DisplayName("a token signed by a different keypair is refused")
    void rejectsAnotherKeypair() {
        String forged = WorkloadToken.issueSigned("web-experience", "dao",
                Set.of("dao:read"), "brand-42", "r", OTHER_KEYS.privateKey(), NOW);

        assertNull(WorkloadToken.verifySigned(forged, "dao", BFF_KEYS.publicKey(), NOW));
    }

    @Test
    @DisplayName("a v2 token is never checked with the HMAC key, and vice versa")
    void schemesDoNotCross() {
        // The alg-confusion class of bug: a token must not be able to select a weaker check. The
        // version fixes the algorithm, so an HMAC key cannot verify a v2 token even if an attacker
        // knows it — and a v1 token is not accepted just because a public key is present.
        String v2 = issueSigned("dao", "brand-42");
        assertNull(WorkloadToken.verify(v2, "dao", null, KEY, NOW));

        String v1 = issue("dao", "brand-42");
        assertNull(WorkloadToken.verify(v1, "dao", BFF_KEYS.publicKey(), null, NOW));
    }

    @Test
    @DisplayName("both schemes verify while both keys are configured")
    void dualAcceptDuringMigration() {
        // Removing v1 outright would force every service to deploy in the same instant.
        assertNotNull(WorkloadToken.verify(issueSigned("dao", "t"), "dao", BFF_KEYS.publicKey(), KEY, NOW));
        assertNotNull(WorkloadToken.verify(issue("dao", "t"), "dao", BFF_KEYS.publicKey(), KEY, NOW));
    }

    @Test
    @DisplayName("the scheme is reported, so legacy acceptances can be counted")
    void reportsItsScheme() {
        // Deleting the shared secret should be evidence-based; this is the evidence.
        assertTrue(WorkloadToken.verify(issueSigned("dao", "t"), "dao", BFF_KEYS.publicKey(), KEY, NOW)
                .isAsymmetric());
        assertFalse(WorkloadToken.verify(issue("dao", "t"), "dao", BFF_KEYS.publicKey(), KEY, NOW)
                .isAsymmetric());
    }

    @Test
    @DisplayName("an unknown version is refused rather than defaulted")
    void refusesAnUnknownVersion() {
        // Defaulting to the weaker scheme would let a caller choose its own algorithm by inventing
        // a prefix.
        String v2 = issueSigned("dao", "t");
        String tampered = "v3" + v2.substring(2);

        assertNull(WorkloadToken.verify(tampered, "dao", BFF_KEYS.publicKey(), KEY, NOW));
    }

    @Test
    @DisplayName("a signed token still expires and is still audience-bound")
    void signedKeepsTheOtherGuarantees() {
        String token = issueSigned("dao", "t");

        assertNull(WorkloadToken.verifySigned(token, "bff", BFF_KEYS.publicKey(), NOW));
        assertNull(WorkloadToken.verifySigned(token, "dao", BFF_KEYS.publicKey(), NOW.plusSeconds(600)));
    }

    @Test
    @DisplayName("generated keypairs are distinct")
    void keypairsAreDistinct() {
        assertFalse(BFF_KEYS.privateKey().equals(OTHER_KEYS.privateKey()));
        assertFalse(BFF_KEYS.publicKey().equals(OTHER_KEYS.publicKey()));
    }
}
