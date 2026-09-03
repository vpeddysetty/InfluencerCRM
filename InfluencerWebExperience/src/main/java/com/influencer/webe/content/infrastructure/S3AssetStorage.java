package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.AssetStoragePort;
import com.influencer.webe.shared.infrastructure.AwsSigV4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Asset bytes in S3 (roadmap PR-45).
 *
 * <p><b>Why this exists, stated accurately.</b> {@link FilesystemAssetStorage} is
 * {@code matchIfMissing = true} and {@code assets.provider} is set nowhere, so production serves
 * uploaded images through the application off a filesystem path. That path is NOT container-local:
 * `WEBE_ASSET_ROOT=/mnt/assets` is an EFS access point, so uploads already survive an instance
 * refresh. The roadmap's "one EC2 box's local disk" reading was out of date, and this class is not
 * the rescue that description implies.
 *
 * <p>What it does buy is real but narrower: bytes served by S3/CloudFront rather than through the
 * BFF's own request threads, an origin Meta's Content Publishing API can fetch server-side (§10.3
 * names object storage as a precondition of that path, not share-kit polish), and an
 * {@code og:image} host for {@code PR-59}. Durability was already handled; reach and offload are
 * what was missing.
 *
 * <p><b>Raw HTTP with SigV4 rather than the AWS SDK</b>, matching {@code ConsentEvidenceWriter},
 * which does the same for the same reason: the SDK is a large dependency for four operations, and
 * {@link AwsSigV4} already signs S3 requests here — it was written for consent evidence and is
 * exercised by 20 tests.
 *
 * <p><b>Keys are generated here, never taken from the caller</b> — the port's contract, and the
 * reason is that a caller-supplied key could traverse out of the brand prefix or overwrite another
 * tenant's object. The format is identical to the filesystem adapter's, so a bucket can be seeded
 * from a disk copy and the two remain interchangeable.
 *
 * <p><b>{@link #urlFor} returns a public URL, not a signed one.</b> These are images embedded in a
 * PUBLIC landing page: a signed URL would expire while the page still referenced it, and every
 * visitor would need a fresh one. The bucket is therefore expected to be readable — by CloudFront
 * in front of it, or by a bucket policy — and nothing private may be stored through this port.
 */
@Component
@ConditionalOnProperty(name = "web-experience.assets.provider", havingValue = "s3")
public class S3AssetStorage implements AssetStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3AssetStorage.class);

    private final String bucket;
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final String publicBaseUrl;
    private final HttpClient httpClient;

    public S3AssetStorage(
            @Value("${web-experience.assets.s3.bucket:}") String bucket,
            @Value("${web-experience.assets.s3.region:us-east-1}") String region,
            @Value("${web-experience.assets.s3.access-key-id:}") String accessKeyId,
            @Value("${web-experience.assets.s3.secret-access-key:}") String secretAccessKey,
            @Value("${web-experience.assets.s3.session-token:}") String sessionToken,
            // Where a BROWSER should fetch these from: a CloudFront domain in production, or the
            // bucket's own endpoint. Separate from the bucket name because the two differ the
            // moment a CDN is in front, and the page embeds whatever this returns.
            @Value("${web-experience.assets.s3.public-base-url:}") String publicBaseUrl) {
        this.bucket = bucket == null ? "" : bucket.trim();
        this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
        this.accessKeyId = accessKeyId == null ? "" : accessKeyId.trim();
        this.secretAccessKey = secretAccessKey == null ? "" : secretAccessKey.trim();
        this.sessionToken = sessionToken == null ? "" : sessionToken.trim();
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        if (this.bucket.isBlank()) {
            // Selected as the provider with no bucket. Failing at construction rather than on the
            // first upload, because "my image vanished" is a much worse way to learn this than a
            // container that refuses to start.
            throw new IllegalStateException(
                    "web-experience.assets.provider=s3 requires web-experience.assets.s3.bucket");
        }
    }

    private String host() {
        return bucket + ".s3." + region + ".amazonaws.com";
    }

    @Override
    public String put(String brandId, String fileName, String contentType, byte[] bytes) {
        // Same key shape as the filesystem adapter, deliberately: the two stay interchangeable and
        // a bucket can be seeded from a disk copy without rewriting any stored key.
        String key = brandId + "/" + UUID.randomUUID() + extensionOf(fileName);
        Map<String, String> headers = AwsSigV4.signS3Put(
                accessKeyId, secretAccessKey, sessionToken, region, host(), key,
                bytes, contentType, null, Instant.now());

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + host() + "/" + key))
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        headers.forEach(request::header);

        try {
            HttpResponse<Void> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                // The status, never the body: an S3 error body can echo the key, and this line
                // goes to a log that is not a secret store.
                log.warn("S3 asset upload failed with status {} for brand {}", response.statusCode(), brandId);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store asset");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store asset", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store asset", e);
        }
        return key;
    }

    @Override
    public String urlFor(String storageKey) {
        // Falls back to the bucket endpoint when no CDN is configured, so a deployment without a
        // distribution still serves working images rather than broken ones.
        String base = publicBaseUrl.isBlank() ? "https://" + host() : publicBaseUrl;
        return base + "/" + storageKey;
    }

    /**
     * Read an object back.
     *
     * <p>The port declares this for the filesystem adapter, which has no public URL and serves
     * bytes through the application. An S3 deployment serves them directly, so this exists for
     * completeness — and returns null rather than throwing on a missing key, which is what the
     * contract says and what {@code AssetService}'s delete path relies on.
     */
    @Override
    public byte[] get(String storageKey) {
        Map<String, String> headers = AwsSigV4.signS3Get(
                accessKeyId, secretAccessKey, sessionToken, region, host(), storageKey, Instant.now());

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + host() + "/" + storageKey))
                .timeout(Duration.ofSeconds(30))
                .GET();
        headers.forEach(request::header);

        try {
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() / 100 == 2 ? response.body() : null;
        } catch (IOException e) {
            log.info("S3 asset read failed for {}: {}", storageKey, e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Idempotent, per the port: S3 answers 204 for a key that was never there, which is correct. */
    @Override
    public void delete(String storageKey) {
        Map<String, String> headers = AwsSigV4.signS3Delete(
                accessKeyId, secretAccessKey, sessionToken, region, host(), storageKey, Instant.now());

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + host() + "/" + storageKey))
                .timeout(Duration.ofSeconds(15))
                .DELETE();
        headers.forEach(request::header);

        try {
            HttpResponse<Void> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                // Logged, not thrown. A failed delete leaves an orphaned object costing fractions
                // of a cent; propagating would fail the user's action of removing an asset from
                // their library, which is the part they can see.
                log.warn("S3 asset delete failed with status {} for {}", response.statusCode(), storageKey);
            }
        } catch (IOException e) {
            log.warn("S3 asset delete failed for {}: {}", storageKey, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Extension only, lowercased, and only when it looks like one. Mirrors the filesystem adapter. */
    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,8}") ? ext : "";
    }
}
