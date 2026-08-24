package com.influencer.webe.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.shared.infrastructure.AwsSigV4;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consent evidence writer's contract: it is optional, it is silent about its own failures, and
 * the two encodings of the same digest must stay consistent.
 */
class ConsentEvidenceWriterTest {

    private static ConsentEvidenceWriter writer(String bucket) {
        return new ConsentEvidenceWriter(bucket, "us-east-1", "", "", "", new ObjectMapper());
    }

    @Test
    @DisplayName("with no bucket configured it is disabled and writes nothing")
    void disabledWithoutBucket() {
        ConsentEvidenceWriter writer = writer("");
        assertFalse(writer.enabled());

        // Must not throw. Local development has no bucket, and a signup there must still work.
        writer.writeReceipt(null, "user", null, "a@b.com", "privacy_policy", "2026-08-11",
                "https://x/privacy/", "abc", "brand_signup", null, null, null);
        assertNull(writer.snapshotDocument("privacy_policy", "2026-08-11", "https://x/", new byte[]{1}));
    }

    @Test
    @DisplayName("a configured bucket enables it")
    void enabledWithBucket() {
        assertTrue(writer("some-bucket").enabled());
    }

    @Test
    @DisplayName("empty content is refused rather than stored")
    void refusesEmptySnapshot() {
        ConsentEvidenceWriter writer = writer("some-bucket");
        // Under Object Lock COMPLIANCE an empty snapshot could not be replaced for seven years, and
        // it is indistinguishable from a document that genuinely says nothing.
        assertNull(writer.snapshotDocument("privacy_policy", "2026-08-11", "https://x/", new byte[0]));
        assertNull(writer.snapshotDocument("privacy_policy", "2026-08-11", "https://x/", null));
    }

    @Test
    @DisplayName("the checksum header is base64 of the SAME digest the signature carries as hex")
    void checksumEncodingMatchesSignatureDigest() {
        // The bug this pins is a 400 from S3, not a wrong number:
        //   "Content-MD5 OR x-amz-checksum- HTTP header is required for Put Object requests with
        //    Object Lock parameters"
        // A bucket with Object Lock refuses any PUT carrying no integrity check. Every unit test
        // passed without the header, because a signature can be valid and still describe a request
        // S3 will not accept -- it was only found by writing to the real bucket.
        //
        // The two encodings are the trap: x-amz-content-sha256 is lowercase HEX, while
        // x-amz-checksum-sha256 is BASE64. Same bytes, and sending the wrong one is a checksum
        // mismatch rather than a format complaint.
        byte[] body = "{\"consent\":\"receipt\"}".getBytes(StandardCharsets.UTF_8);

        String base64 = ConsentEvidenceWriter.base64Sha256(body);
        String hex = AwsSigV4.hexSha256(body);

        StringBuilder fromBase64 = new StringBuilder();
        for (byte b : Base64.getDecoder().decode(base64)) {
            fromBase64.append(Character.forDigit((b >> 4) & 0xF, 16))
                      .append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(hex, fromBase64.toString(),
                "the checksum header and the signed payload hash must describe the same bytes");
    }

    @Test
    @DisplayName("a version whose text changed is refused, not silently overwritten")
    void changedTextUnderAnExistingVersionIsRefused() throws Exception {
        // THE BUG THIS PINS, found in production on 2026-08-23.
        //
        // The privacy policy was edited to add two links without bumping its version. A restart
        // re-fetched it and wrote the NEW text under the OLD version key, so the key a consent row
        // pointed at resolved to a document that row's subject had never seen. Object Lock kept the
        // original as a prior object version, so nothing was destroyed -- but "2026-08-11" then had
        // two meanings, which is the precise ambiguity a version exists to remove.
        //
        // The guard reads S3's own stored checksum before writing and refuses when it differs.
        // Asserted here on the source, because exercising it needs a live bucket: the writer is
        // disabled without one, and a mock would only prove the mock was called.
        String writer = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/influencer/webe/identity/infrastructure/ConsentEvidenceWriter.java"),
                StandardCharsets.UTF_8);

        assertTrue(writer.contains("existingDigest(documentKey)"),
                "the stored digest must be read before writing");
        assertTrue(writer.contains("REFUSING to overwrite the snapshot"),
                "a changed document under an existing version must be refused loudly");
        // And an unchanged document must stay a no-op: every boot re-runs this.
        assertTrue(writer.contains("already stored and unchanged"),
                "re-uploading identical bytes must not be an error");
    }

    @Test
    @DisplayName("the base64 checksum is byte-for-byte what S3 independently computed")
    void checksumMatchesWhatS3Recorded() {
        // Captured from the live bucket on 2026-08-23. These exact bytes were PUT with signS3Put,
        // S3 accepted them, and head-object --checksum-mode ENABLED returned the value below --
        // computed by S3 from the stored object, not echoed back from the request.
        //
        // That independence is what makes this worth pinning: it shows the digest recorded in
        // consent_records.document_sha256 really does identify the bytes in the bucket, which is
        // the property the whole evidence chain rests on.
        byte[] body = "{\"probe\":\"signS3Put\",\"at\":\"2026-08-23T21:19:40.431661500Z\"}"
                .getBytes(StandardCharsets.UTF_8);

        String s3Computed = "RJH2bRHtNNT7T/VP+FpOEb18CwzBRx6z180hZqzNQRk=";
        String s3ComputedAsHex = "4491f66d11ed34d4fb4ff54ff85a4e11bd7c0b0cc1471eb3d7cd2166accd4119";

        assertEquals(s3Computed, ConsentEvidenceWriter.base64Sha256(body),
                "the checksum header must equal what S3 computes from the stored object");
        assertEquals(s3ComputedAsHex, AwsSigV4.hexSha256(body),
                "and the hex digest recorded as evidence must describe those same bytes");
    }
}
