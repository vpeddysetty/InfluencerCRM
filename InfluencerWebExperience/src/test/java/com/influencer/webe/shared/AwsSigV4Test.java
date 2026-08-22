package com.influencer.webe.shared;

import com.influencer.webe.shared.infrastructure.AwsSigV4;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request signing for SES.
 *
 * <p>Signing is the part of this integration that cannot be "nearly right": AWS returns
 * {@code SignatureDoesNotMatch} for any divergence and says nothing about which detail was wrong.
 * These tests pin the properties that are easy to break silently.
 *
 * <p>They are deterministic because the timestamp is injected rather than read from the clock —
 * which is also why {@code AwsSigV4} takes an {@link Instant} instead of calling
 * {@code Instant.now()} internally.
 */
class AwsSigV4Test {

    private static final String KEY_ID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final Instant WHEN = Instant.parse("2026-08-09T12:00:00Z");
    private static final String HOST = "email.us-east-1.amazonaws.com";
    private static final String PATH = "/v2/email/outbound-emails";
    private static final String BODY = "{\"FromEmailAddress\":\"a@b.test\"}";

    private Map<String, String> sign(String body, Instant when) {
        return AwsSigV4.signJsonPost(KEY_ID, SECRET, null, "us-east-1", "ses", HOST, PATH, body, when);
    }

    @Test
    @DisplayName("the Authorization header has the four parts AWS requires")
    void authorizationShape() {
        String auth = sign(BODY, WHEN).get("Authorization");

        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "), auth);
        assertTrue(auth.contains("Credential=" + KEY_ID + "/20260809/us-east-1/ses/aws4_request"), auth);
        assertTrue(auth.contains("SignedHeaders="), auth);
        assertTrue(auth.contains("Signature="), auth);
    }

    @Test
    @DisplayName("SignedHeaders lists exactly the headers sent, sorted and lowercased")
    void signedHeadersMatchWhatIsSent() {
        // The most common cause of SignatureDoesNotMatch: AWS recomputes from the headers it
        // receives, so the list and the request must agree exactly.
        Map<String, String> headers = sign(BODY, WHEN);
        String signedHeaders = extract(headers.get("Authorization"), "SignedHeaders=");

        assertEquals("content-type;host;x-amz-content-sha256;x-amz-date", signedHeaders);
        for (String name : signedHeaders.split(";")) {
            assertTrue(headers.containsKey(name), "signed but not sent: " + name);
        }
    }

    @Test
    @DisplayName("the date appears identically in the header and the credential scope")
    void dateIsConsistent() {
        // It is used in three places and must agree in all of them.
        Map<String, String> headers = sign(BODY, WHEN);

        assertEquals("20260809T120000Z", headers.get("x-amz-date"));
        assertTrue(headers.get("Authorization").contains("/20260809/"));
    }

    @Test
    @DisplayName("a different body produces a different signature")
    void signatureCoversTheBody() {
        // If it did not, an intercepted request could have its payload swapped — a signature over
        // a body that was never sent.
        String one = extract(sign(BODY, WHEN).get("Authorization"), "Signature=");
        String two = extract(sign("{\"FromEmailAddress\":\"evil@b.test\"}", WHEN).get("Authorization"), "Signature=");

        assertNotEquals(one, two);
    }

    @Test
    @DisplayName("a different timestamp produces a different signature")
    void signatureCoversTheTimestamp() {
        // This is what bounds replay: a captured request stops verifying once its window passes.
        String one = extract(sign(BODY, WHEN).get("Authorization"), "Signature=");
        String two = extract(sign(BODY, WHEN.plusSeconds(60)).get("Authorization"), "Signature=");

        assertNotEquals(one, two);
    }

    @Test
    @DisplayName("signing is deterministic for the same inputs")
    void deterministic() {
        // Not merely a nicety: a non-deterministic signer cannot be tested, and the bug would only
        // appear as intermittent auth failures in production.
        assertEquals(sign(BODY, WHEN).get("Authorization"), sign(BODY, WHEN).get("Authorization"));
    }

    @Test
    @DisplayName("the payload hash header matches the body, and an empty body still signs")
    void payloadHash() {
        // SHA-256 of "" — the documented value for an empty payload, worth pinning because an
        // empty body is the case a hand-rolled hasher is most likely to special-case wrongly.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                sign("", WHEN).get("x-amz-content-sha256"));
        assertNotEquals(sign("", WHEN).get("x-amz-content-sha256"),
                sign(BODY, WHEN).get("x-amz-content-sha256"));
    }

    @Test
    @DisplayName("a session token is signed when present and absent when not")
    void sessionTokenIsPartOfTheSignature() {
        // Temporary credentials fail if the token is added to the request but not the signature.
        Map<String, String> withToken = AwsSigV4.signJsonPost(
                KEY_ID, SECRET, "FQoGZXIvYXdzEExample", "us-east-1", "ses", HOST, PATH, BODY, WHEN);

        assertTrue(withToken.containsKey("x-amz-security-token"));
        assertTrue(extract(withToken.get("Authorization"), "SignedHeaders=")
                .contains("x-amz-security-token"));

        assertFalse(sign(BODY, WHEN).containsKey("x-amz-security-token"),
                "a long-lived IAM user must not send an empty token header");
    }

    @Test
    @DisplayName("the secret key never appears in the output")
    void doesNotLeakTheSecret() {
        // These headers are logged in some environments.
        Map<String, String> headers = sign(BODY, WHEN);
        headers.values().forEach(value -> assertFalse(value.contains(SECRET), "secret leaked"));
    }

    private String extract(String authorization, String field) {
        int at = authorization.indexOf(field) + field.length();
        int end = authorization.indexOf(',', at);
        return (end < 0 ? authorization.substring(at) : authorization.substring(at, end)).trim();
    }

    @Test
    @DisplayName("host is signed but must never be set on a java.net.http request")
    void hostIsSignedYetUnsettable() {
        Map<String, String> headers = AwsSigV4.signJsonPost(
                KEY_ID, SECRET, null, "us-east-1", "ses", HOST,
                "/v2/email/outbound-emails", "{}", WHEN);

        // Signed: SES rejects the request if host is absent from SignedHeaders.
        assertTrue(headers.get("Authorization").contains("host"),
                "host must appear in SignedHeaders or SES rejects the signature");

        // Yet a caller must skip it when building the request. java.net.http.HttpClient reserves
        // the name and throws IllegalArgumentException("restricted header name: host"), setting it
        // itself from the URI. Every real SES send failed on this until SesEmailSender filtered it
        // out - the exception reads like a signing fault and is not one.
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://" + HOST + "/v2/email/outbound-emails"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.header("host", HOST),
                "if this ever stops throwing, the filter in SesEmailSender is no longer needed");

        // The filter every caller has to apply.
        headers.forEach((name, value) -> {
            if (!"host".equalsIgnoreCase(name)) {
                builder.header(name, value);
            }
        });
        assertNotNull(builder.build(), "the request must build once host is filtered out");
    }
}
