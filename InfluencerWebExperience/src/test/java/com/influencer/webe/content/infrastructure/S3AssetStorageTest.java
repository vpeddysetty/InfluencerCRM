package com.influencer.webe.content.infrastructure;

import com.influencer.webe.shared.infrastructure.AwsSigV4;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3 as the asset backend (roadmap PR-45).
 *
 * <p><b>What this can and cannot cover.</b> The four operations are HTTP calls to AWS, so the
 * request-building and the signature are what is testable here; the round trip belongs to a live
 * environment. That split is deliberate — the failures worth catching early are a signature that
 * differs by verb, a key format that diverges from the filesystem adapter, and a misconfiguration
 * that fails at the first upload instead of at startup.
 */
class S3AssetStorageTest {

    private S3AssetStorage storage(String bucket, String publicBaseUrl) {
        return new S3AssetStorage(bucket, "us-east-1", "AKIAEXAMPLE", "secret", "", publicBaseUrl);
    }

    @Test
    @DisplayName("selecting s3 with no bucket fails at startup, not at the first upload")
    void refusesWithoutABucket() {
        // "My image vanished" is a much worse way to learn the provider is misconfigured than a
        // container that declines to start.
        assertThatThrownBy(() -> storage("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assets.s3.bucket");
    }

    @Test
    @DisplayName("a CDN base URL is what the page embeds, when one is configured")
    void prefersTheCdnUrl() {
        assertThat(storage("influencrm-assets", "https://cdn.tejdux.com").urlFor("brand/abc.png"))
                .isEqualTo("https://cdn.tejdux.com/brand/abc.png");
    }

    @Test
    @DisplayName("with no CDN it falls back to the bucket endpoint rather than serving nothing")
    void fallsBackToTheBucket() {
        assertThat(storage("influencrm-assets", "").urlFor("brand/abc.png"))
                .isEqualTo("https://influencrm-assets.s3.us-east-1.amazonaws.com/brand/abc.png");
    }

    @Test
    @DisplayName("a trailing slash on the base URL does not produce a double slash")
    void normalisesTheBaseUrl() {
        // Some CDNs 404 a doubled slash rather than normalising it, and the failure looks like a
        // missing image rather than a malformed URL.
        assertThat(storage("b", "https://cdn.tejdux.com/").urlFor("brand/abc.png"))
                .isEqualTo("https://cdn.tejdux.com/brand/abc.png");
    }

    @Test
    @DisplayName("GET, HEAD and DELETE each sign their own verb")
    void verbIsPartOfTheSignature() {
        // The verb is part of the canonical request. Signing a GET as a HEAD yields a 403 whose
        // message names neither -- which is exactly the afternoon the shared helper exists to
        // prevent, and the risk introduced by extracting it.
        Instant now = Instant.parse("2026-09-02T12:00:00Z");
        Map<String, String> get = AwsSigV4.signS3Get("k", "s", "", "us-east-1", "b.s3.amazonaws.com", "x.png", now);
        Map<String, String> del = AwsSigV4.signS3Delete("k", "s", "", "us-east-1", "b.s3.amazonaws.com", "x.png", now);
        Map<String, String> head = AwsSigV4.signS3Head("k", "s", "", "us-east-1", "b.s3.amazonaws.com", "x.png", null, now);

        assertThat(get.get("Authorization")).isNotEqualTo(del.get("Authorization"));
        assertThat(get.get("Authorization")).isNotEqualTo(head.get("Authorization"));
        assertThat(del.get("Authorization")).isNotEqualTo(head.get("Authorization"));
    }

    @Test
    @DisplayName("every signature still carries the headers S3 requires")
    void signatureShapeIsUnchanged() {
        Instant now = Instant.parse("2026-09-02T12:00:00Z");
        Map<String, String> signed = AwsSigV4.signS3Get("k", "s", "", "us-east-1", "b.s3.amazonaws.com", "x.png", now);

        assertThat(signed).containsKeys("Authorization", "host", "x-amz-content-sha256", "x-amz-date");
        assertThat(signed.get("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=k/");
    }

    @Test
    @DisplayName("a session token is signed when present and absent when not")
    void sessionTokenIsOptional() {
        // Instance-role credentials carry one; a static key pair does not. Signing a blank token
        // would produce a signature S3 rejects.
        Instant now = Instant.parse("2026-09-02T12:00:00Z");

        assertThat(AwsSigV4.signS3Get("k", "s", "tok", "us-east-1", "b.s3.amazonaws.com", "x.png", now))
                .containsKey("x-amz-security-token");
        assertThat(AwsSigV4.signS3Get("k", "s", "", "us-east-1", "b.s3.amazonaws.com", "x.png", now))
                .doesNotContainKey("x-amz-security-token");
    }
}
