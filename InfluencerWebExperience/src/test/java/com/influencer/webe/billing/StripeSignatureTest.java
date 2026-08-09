package com.influencer.webe.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the only thing standing between a forged webhook and a free agency plan.
 *
 * <p>The billing webhook endpoint cannot require a user token — Stripe holds none — so the
 * signature <em>is</em> the authentication. Every failure mode here is silent and severe: a check
 * that accepts anything hands out paid plans to whoever knows the URL, and one that rejects
 * everything makes real subscriptions never activate while Stripe retries into a void.
 */
class StripeSignatureTest {

    private static final String SECRET = "whsec_test_secret_value";
    private static final String BODY = "{\"id\":\"evt_123\",\"type\":\"customer.subscription.updated\"}";
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    /** Builds the header Stripe would send for this body at this time. */
    private static String signedHeader(String body, String secret, Instant at) {
        long timestamp = at.getEpochSecond();
        return "t=" + timestamp + ",v1=" + StripeSignature.hmacSha256Hex(timestamp + "." + body, secret);
    }

    @Test
    @DisplayName("a correctly signed request verifies")
    void validSignaturePasses() {
        assertTrue(StripeSignature.verify(signedHeader(BODY, SECRET, NOW), BODY, SECRET, NOW));
    }

    @Test
    @DisplayName("a body altered after signing is refused")
    void tamperedBodyFails() {
        // The attack this exists to stop: capture a real event, change which account or plan it
        // names, replay it. The signature covers the body, so any edit breaks it.
        String header = signedHeader(BODY, SECRET, NOW);
        String tampered = BODY.replace("evt_123", "evt_999");

        assertFalse(StripeSignature.verify(header, tampered, SECRET, NOW));
    }

    @Test
    @DisplayName("a signature made with the wrong secret is refused")
    void wrongSecretFails() {
        String header = signedHeader(BODY, "whsec_someone_elses_secret", NOW);

        assertFalse(StripeSignature.verify(header, BODY, SECRET, NOW));
    }

    @Test
    @DisplayName("an old signature is refused even though it is valid")
    void staleSignatureFails() {
        // A signature never expires on its own, so without a freshness window a captured request
        // could be replayed forever. Five minutes matches Stripe's own recommendation.
        Instant signedLongAgo = NOW.minus(Duration.ofMinutes(10));
        String header = signedHeader(BODY, SECRET, signedLongAgo);

        assertFalse(StripeSignature.verify(header, BODY, SECRET, NOW),
                "a 10-minute-old signature must be refused");
        // Just inside the window still passes, so ordinary clock drift does not break delivery.
        assertTrue(StripeSignature.verify(
                signedHeader(BODY, SECRET, NOW.minus(Duration.ofMinutes(4))), BODY, SECRET, NOW));
    }

    @Test
    @DisplayName("a future-dated signature is refused too")
    void futureSignatureFails() {
        // Guarded in both directions. Accepting a far-future timestamp would let a captured
        // request be held and replayed whenever it suited.
        String header = signedHeader(BODY, SECRET, NOW.plus(Duration.ofHours(2)));

        assertFalse(StripeSignature.verify(header, BODY, SECRET, NOW));
    }

    @Test
    @DisplayName("a missing, empty or malformed header verifies nothing")
    void malformedHeaderFails() {
        // The open-door cases. Each of these must fail closed rather than being treated as
        // "nothing to check, so allow it".
        assertFalse(StripeSignature.verify(null, BODY, SECRET, NOW));
        assertFalse(StripeSignature.verify("", BODY, SECRET, NOW));
        assertFalse(StripeSignature.verify("garbage", BODY, SECRET, NOW));
        assertFalse(StripeSignature.verify("t=notanumber,v1=abc", BODY, SECRET, NOW));
        // A timestamp with no signature at all.
        assertFalse(StripeSignature.verify("t=" + NOW.getEpochSecond(), BODY, SECRET, NOW));
        // A signature with no timestamp — unbounded replay if accepted.
        assertFalse(StripeSignature.verify(
                "v1=" + StripeSignature.hmacSha256Hex(NOW.getEpochSecond() + "." + BODY, SECRET),
                BODY, SECRET, NOW));
    }

    @Test
    @DisplayName("no secret means nothing verifies")
    void missingSecretFails() {
        // Belt and braces: the controller already refuses with 503 before reaching here, but a
        // verifier that passed on an empty secret would be a trapdoor if that guard ever moved.
        String header = signedHeader(BODY, SECRET, NOW);

        assertFalse(StripeSignature.verify(header, BODY, null, NOW));
        assertFalse(StripeSignature.verify(header, BODY, "", NOW));
    }

    @Test
    @DisplayName("during a secret rotation, either valid signature is accepted")
    void multipleSignaturesAreAllChecked() {
        // Stripe sends one v1 per active secret while a rotation is in flight. Stopping at the
        // first would reject requests signed with the other still-valid secret — an outage caused
        // by doing the safe thing.
        long timestamp = NOW.getEpochSecond();
        String ours = StripeSignature.hmacSha256Hex(timestamp + "." + BODY, SECRET);
        String theirs = StripeSignature.hmacSha256Hex(timestamp + "." + BODY, "whsec_the_old_one");

        assertTrue(StripeSignature.verify("t=" + timestamp + ",v1=" + theirs + ",v1=" + ours,
                BODY, SECRET, NOW), "ours listed second must still be found");
        assertTrue(StripeSignature.verify("t=" + timestamp + ",v1=" + ours + ",v1=" + theirs,
                BODY, SECRET, NOW), "ours listed first must still verify");
    }

    @Test
    @DisplayName("whitespace and unknown scheme fields are tolerated")
    void toleratesRealHeaderShape() {
        // Stripe's header carries v0 alongside v1 for some event types, and the parts arrive with
        // spacing. Being strict here would reject genuine events.
        long timestamp = NOW.getEpochSecond();
        String v1 = StripeSignature.hmacSha256Hex(timestamp + "." + BODY, SECRET);

        assertTrue(StripeSignature.verify(
                "t=" + timestamp + ", v0=ignored, v1=" + v1, BODY, SECRET, NOW));
    }

    @Test
    @DisplayName("an empty body is signed and verified like any other")
    void emptyBodyIsHandled() {
        // Not a special case, but it must not throw — an empty POST is a reachable input.
        assertTrue(StripeSignature.verify(signedHeader("", SECRET, NOW), "", SECRET, NOW));
        assertFalse(StripeSignature.verify(signedHeader("", SECRET, NOW), BODY, SECRET, NOW));
    }
}
