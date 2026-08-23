package com.influencer.webe.shared.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S3 PUT signature, and the key encoding it depends on.
 *
 * <p>A wrong signature here is not a subtle bug — S3 answers 403 and the consent receipt is simply
 * never written, on a path that deliberately swallows its own failures. So the parts that are easy
 * to get wrong and impossible to notice are pinned here rather than discovered in production.
 */
class AwsSigV4S3Test {

    private static final Instant WHEN = Instant.parse("2026-08-23T12:00:00Z");

    private static Map<String, String> sign(String key, byte[] body) {
        return AwsSigV4.signS3Put("AKIAEXAMPLE", "secret", null, "us-east-1",
                "bucket.s3.us-east-1.amazonaws.com", key, body, "application/json",
                new HashMap<>(), WHEN);
    }

    @Test
    @DisplayName("the payload hash is the digest of the BYTES, matching what is stored as evidence")
    void payloadHashIsOverBytes() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = sign("receipts/x.json", body);

        // This is the whole point of hashing bytes rather than a String: the value S3 verifies and
        // the value recorded in consent_records.document_sha256 must be one number.
        assertEquals(AwsSigV4.hexSha256(body), headers.get("x-amz-content-sha256"));
    }

    @Test
    @DisplayName("a non-UTF-8 document is not corrupted by a charset round trip")
    void nonUtf8BytesHashStably() {
        // 0x93 and 0x94 are smart quotes in windows-1252 and are NOT valid UTF-8. Decoding them to
        // a String and re-encoding replaces both with U+FFFD, which changes the digest — the exact
        // corruption that would make a stored hash disagree with the stored object.
        byte[] cp1252 = new byte[]{'h', 'i', (byte) 0x93, 'x', (byte) 0x94};
        String viaString = AwsSigV4.hexSha256(
                new String(cp1252, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));

        assertNotEquals(viaString, AwsSigV4.hexSha256(cp1252),
                "if these are equal the test data no longer exercises the round-trip hazard");
        assertEquals(AwsSigV4.hexSha256(cp1252), sign("k", cp1252).get("x-amz-content-sha256"));
    }

    @Test
    @DisplayName("every signed header appears in SignedHeaders, and host is among them")
    void signedHeadersAreComplete() {
        Map<String, String> headers = sign("receipts/x.json", "{}".getBytes(StandardCharsets.UTF_8));
        String auth = headers.get("Authorization");

        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "), auth);
        assertTrue(auth.contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"),
                "unexpected SignedHeaders in: " + auth);
        assertTrue(auth.contains("/us-east-1/s3/aws4_request"), "wrong credential scope: " + auth);
    }

    @Test
    @DisplayName("a session token is signed when present and absent when not")
    void sessionTokenIsSignedOnlyWhenPresent() {
        Map<String, String> without = sign("k", "{}".getBytes(StandardCharsets.UTF_8));
        assertFalse(without.containsKey("x-amz-security-token"));

        Map<String, String> with = AwsSigV4.signS3Put("AKIA", "secret", "TOKEN", "us-east-1",
                "bucket.s3.us-east-1.amazonaws.com", "k", "{}".getBytes(StandardCharsets.UTF_8),
                "application/json", new HashMap<>(), WHEN);

        assertEquals("TOKEN", with.get("x-amz-security-token"));
        // Signed, not merely sent: a token attached after signing is rejected.
        assertTrue(with.get("Authorization").contains("x-amz-security-token"),
                "the token must be part of SignedHeaders");
    }

    @Test
    @DisplayName("extra headers are signed, and case-folded so they sort correctly")
    void extraHeadersAreSignedAndLowercased() {
        Map<String, String> extra = new HashMap<>();
        extra.put("X-Amz-Object-Lock-Mode", "COMPLIANCE");

        Map<String, String> headers = AwsSigV4.signS3Put("AKIA", "secret", null, "us-east-1",
                "bucket.s3.us-east-1.amazonaws.com", "k", "{}".getBytes(StandardCharsets.UTF_8),
                "application/json", extra, WHEN);

        assertEquals("COMPLIANCE", headers.get("x-amz-object-lock-mode"));
        assertTrue(headers.get("Authorization").contains("x-amz-object-lock-mode"));
    }

    @Test
    @DisplayName("the same inputs sign identically, and any byte change alters the signature")
    void signatureIsDeterministicAndBodySensitive() {
        byte[] one = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] two = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);

        assertEquals(sign("k", one).get("Authorization"), sign("k", one).get("Authorization"));
        assertNotEquals(sign("k", one).get("Authorization"), sign("k", two).get("Authorization"));
        // And a different key must not sign the same, or one object could be written over another.
        assertNotEquals(sign("k", one).get("Authorization"), sign("k2", one).get("Authorization"));
    }

    // -----------------------------------------------------------------------
    // Key encoding
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("slashes stay separators; unreserved characters pass through untouched")
    void ordinaryKeysAreUnchanged() {
        assertEquals("documents/privacy_policy/2026-08-11/document.html",
                AwsSigV4.encodeS3Key("documents/privacy_policy/2026-08-11/document.html"));
        assertEquals("receipts/2026/08/23/a-b_c.d~e.json",
                AwsSigV4.encodeS3Key("receipts/2026/08/23/a-b_c.d~e.json"));
    }

    @Test
    @DisplayName("a space becomes %20 and not '+', which is the form-encoding trap")
    void spaceIsPercentEncoded() {
        // URLEncoder would produce "a+b". S3 would then store a key containing a literal plus.
        assertEquals("a%20b", AwsSigV4.encodeS3Key("a b"));
    }

    @Test
    @DisplayName("percent escapes are uppercase, as the canonical request requires")
    void escapesAreUppercase() {
        String encoded = AwsSigV4.encodeS3Key("a:b");
        assertEquals("a%3Ab", encoded);
        assertFalse(encoded.contains("%3a"), "lowercase hex yields a signature S3 rejects");
    }

    @Test
    @DisplayName("non-ASCII is encoded per UTF-8 byte")
    void nonAsciiIsEncodedPerByte() {
        // é is two bytes in UTF-8: C3 A9.
        assertEquals("caf%C3%A9", AwsSigV4.encodeS3Key("café"));
    }
}
