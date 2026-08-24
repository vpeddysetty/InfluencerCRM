package com.influencer.webe.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.AwsSigV4;
import com.influencer.webe.shared.infrastructure.InstanceRoleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes consent evidence to the object store: document snapshots, and a receipt per acceptance.
 *
 * <h2>What problem this closes</h2>
 *
 * <p>V36 recorded that someone accepted version {@code 2026-08-11} of the privacy policy. That
 * answers <em>which</em> and not <em>what</em>: the published page is a mutable S3 object, so
 * republishing it leaves the recorded version pointing at text nobody kept. This writes the bytes
 * somewhere they cannot be revised — see {@code infrastructure/test/terraform/consent-evidence.tf},
 * where the bucket is created with Object Lock in COMPLIANCE mode.
 *
 * <h2>It must never fail a signup</h2>
 *
 * <p>Every method here swallows its failures and logs at ERROR. By the time evidence is written the
 * account exists, and throwing would hand the caller an error for an account that was in fact
 * created — the worst of both outcomes, and the same reasoning {@code ConsentService} already
 * applies to the database write. A missing receipt is recoverable from the log and from the
 * database row; a phantom failed signup is not.
 *
 * <p>The consequence is deliberate and worth stating plainly: <b>this is best-effort evidence.</b>
 * The authoritative record is the Postgres row. This makes that row checkable.
 *
 * <h2>Why the hash comes from here and not from the caller</h2>
 *
 * <p>{@link #snapshotDocument} returns the digest it actually stored, computed over the same byte
 * array it uploaded and signed. A caller that computed its own hash could record a value that does
 * not match the stored object — for instance by hashing a String decoded with a different charset —
 * and a hash that does not match the bytes is worse than no hash: it reads as evidence and fails
 * the moment anyone checks it.
 *
 * <h2>Absent by default</h2>
 *
 * <p>With no bucket configured, every method returns without doing anything and says so once at
 * startup. Local development has no bucket, no instance role and no business writing seven-year
 * immutable objects.
 */
@Component
public class ConsentEvidenceWriter {

    private static final Logger log = LoggerFactory.getLogger(ConsentEvidenceWriter.class);

    private static final DateTimeFormatter DAY_PATH =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    /** Long enough for a document upload, short enough that a stall does not hold a request. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String bucket;
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final InstanceRoleCredentials instanceRole;

    public ConsentEvidenceWriter(
            @Value("${web-experience.consent.evidence.bucket:}") String bucket,
            @Value("${web-experience.consent.evidence.region:us-east-1}") String region,
            @Value("${web-experience.consent.evidence.access-key-id:}") String accessKeyId,
            @Value("${web-experience.consent.evidence.secret-access-key:}") String secretAccessKey,
            @Value("${web-experience.consent.evidence.session-token:}") String sessionToken,
            ObjectMapper objectMapper) {

        this.bucket = bucket == null ? "" : bucket.trim();
        this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.instanceRole = new InstanceRoleCredentials(this.httpClient, objectMapper);

        if (this.bucket.isEmpty()) {
            log.info("Consent evidence store not configured; receipts will not be written. "
                    + "Set web-experience.consent.evidence.bucket to enable.");
        } else {
            log.info("Consent evidence store: s3://{} in {}", this.bucket, this.region);
        }
    }

    public boolean enabled() {
        return !bucket.isEmpty();
    }

    /**
     * Stores the exact bytes of one version of one document, and returns its digest.
     *
     * @return the lowercase hex SHA-256 actually stored, or null if nothing was written. A null is
     *         the signal to record no hash rather than an unverifiable one.
     */
    public Snapshot snapshotDocument(String consentType, String version, String url, byte[] content) {
        if (!enabled()) {
            return null;
        }
        if (content == null || content.length == 0) {
            // An empty snapshot would be indistinguishable from a document that says nothing, and
            // under COMPLIANCE it could not be replaced for seven years.
            log.error("Refusing to snapshot empty content for {} {}", consentType, version);
            return null;
        }
        try {
            String digest = AwsSigV4.hexSha256(content);
            String base = "documents/" + consentType + "/" + version;
            String documentKey = base + "/document.html";

            // REFUSE TO WRITE DIFFERENT BYTES UNDER AN EXISTING VERSION.
            //
            // Found in production on 2026-08-23. The privacy policy was edited to add two links
            // without bumping its version, and a restart re-fetched it and wrote the NEW text under
            // the OLD version key. Object Lock preserved the original as a prior object version, so
            // no evidence was destroyed -- but the key that a consent row points at now resolved to
            // a document that row's subject never saw, which is exactly the ambiguity the version
            // is supposed to remove.
            //
            // Checking the digest first turns "silently record the wrong text" into "log loudly and
            // change nothing". A version is a promise that the bytes behind it are fixed; the fix
            // for changed text is a NEW version, never a second meaning for an old one.
            //
            // Re-uploading IDENTICAL bytes stays a no-op rather than an error: every boot re-runs
            // this, and an unchanged document is the normal case, not a fault.
            String existing = existingDigest(documentKey);
            if (existing != null) {
                if (existing.equals(digest)) {
                    log.debug("Consent snapshot for {} {} already stored and unchanged",
                            consentType, version);
                    return new Snapshot(digest, documentKey, content.length);
                }
                log.error("REFUSING to overwrite the snapshot of {} {}: stored sha256 {} but the "
                        + "published document now hashes to {}. The document changed without its "
                        + "version being bumped. Publish it as a new version -- consent recorded "
                        + "against {} must keep resolving to the text those users accepted.",
                        consentType, version, existing, digest, version);
                return null;
            }

            boolean stored = put(documentKey, content, "text/html; charset=utf-8");
            if (!stored) {
                return null;
            }

            ObjectNode manifest = objectMapper.createObjectNode();
            manifest.put("consent_type", consentType);
            manifest.put("version", version);
            manifest.put("url", url);
            manifest.put("sha256", digest);
            manifest.put("bytes", content.length);
            manifest.put("captured_at", Instant.now().toString());
            manifest.put("document_key", documentKey);
            byte[] manifestBytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(manifest);
            put(base + "/manifest.json", manifestBytes, "application/json");

            log.info("Consent snapshot stored: {} {} ({} bytes, sha256 {})",
                    consentType, version, content.length, digest);
            return new Snapshot(digest, documentKey, content.length);
        } catch (Exception e) {
            log.error("Failed to snapshot {} {}: {}", consentType, version, e.toString());
            return null;
        }
    }

    /**
     * Writes the receipt for one acceptance.
     *
     * <p>Keyed by date then consent id so an operator can find a day's receipts without listing the
     * whole bucket, and so two acceptances can never collide on a key.
     */
    public void writeReceipt(UUID consentId,
                             String subjectType,
                             UUID subjectId,
                             String subjectEmail,
                             String consentType,
                             String documentVersion,
                             String documentUrl,
                             String documentSha256,
                             String source,
                             String ipAddress,
                             String userAgent,
                             Instant acceptedAt) {
        if (!enabled()) {
            return;
        }
        try {
            Instant when = acceptedAt == null ? Instant.now() : acceptedAt;
            ObjectNode receipt = objectMapper.createObjectNode();
            receipt.put("consent_id", consentId == null ? null : consentId.toString());
            receipt.put("subject_type", subjectType);
            receipt.put("subject_id", subjectId == null ? null : subjectId.toString());
            receipt.put("subject_email", subjectEmail);
            receipt.put("consent_type", consentType);
            receipt.put("document_version", documentVersion);
            receipt.put("document_url", documentUrl);
            receipt.put("document_sha256", documentSha256);
            receipt.put("source", source);
            receipt.put("ip_address", ipAddress);
            receipt.put("user_agent", userAgent);
            receipt.put("accepted_at", when.toString());

            String key = "receipts/" + DAY_PATH.format(when) + "/"
                    + (consentId == null ? UUID.randomUUID() : consentId) + ".json";
            put(key, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt),
                    "application/json");
        } catch (Exception e) {
            // Never rethrown. See the class comment: the account already exists.
            log.error("Failed to write consent receipt for {} {}: {}", subjectType, subjectId, e.toString());
        }
    }

    /** One stored snapshot: the digest, where it went, and how big it was. */
    public record Snapshot(String sha256, String s3Key, int bytes) { }

    /**
     * The SHA-256 of an object already in the bucket, or null when there is none.
     *
     * <p>Reads S3's own {@code x-amz-checksum-sha256}, which S3 computed from the stored bytes when
     * the object was written. That is stronger than trusting the manifest we wrote beside it: the
     * manifest is our claim about the object, while this is the object store's.
     *
     * <p>A failure here returns null, which lets the write proceed. The alternative -- treating an
     * unreadable HEAD as "something is there" -- would block the first snapshot on any transient
     * error and leave consent recorded with no evidence at all.
     */
    private String existingDigest(String key) {
        InstanceRoleCredentials.Credentials temporary = instanceRole.current();
        String keyId = temporary != null ? temporary.accessKeyId() : accessKeyId;
        String secret = temporary != null ? temporary.secretAccessKey() : secretAccessKey;
        String token = temporary != null ? temporary.sessionToken() : sessionToken;
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            return null;
        }

        String host = bucket + ".s3." + region + ".amazonaws.com";
        try {
            Map<String, String> extra = new HashMap<>();
            // Without this S3 omits the checksum from the response entirely.
            extra.put("x-amz-checksum-mode", "ENABLED");

            Map<String, String> headers = AwsSigV4.signS3Head(
                    keyId, secret, token, region, host, key, extra, Instant.now());

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + host + "/" + AwsSigV4.encodeS3Key(key)))
                    .timeout(TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            headers.forEach((name, value) -> {
                if (!"host".equalsIgnoreCase(name)) {
                    request.header(name, value);
                }
            });

            HttpResponse<Void> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() / 100 != 2) {
                log.warn("Could not read the stored checksum for {}: HTTP {}", key, response.statusCode());
                return null;
            }
            String base64 = response.headers().firstValue("x-amz-checksum-sha256").orElse(null);
            if (base64 == null) {
                return null;
            }
            // S3 reports base64; everything else in this feature speaks lowercase hex.
            StringBuilder hex = new StringBuilder(64);
            for (byte b : java.util.Base64.getDecoder().decode(base64)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            log.warn("Could not check the stored snapshot at {}: {}", key, e.toString());
            return null;
        }
    }

    /**
     * The SHA-256 of the body, base64-encoded for {@code x-amz-checksum-sha256}.
     *
     * <p>Note the encoding differs from every other hash in this feature: S3 wants base64 here,
     * while the signature's {@code x-amz-content-sha256} and the digest recorded in
     * {@code consent_records.document_sha256} are lowercase hex. Same bytes, two encodings, and
     * sending hex in this header produces a checksum mismatch rather than a format complaint.
     */
    static String base64Sha256(byte[] body) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.Base64.getEncoder().encodeToString(digest.digest(body));
        } catch (Exception broken) {
            throw new IllegalStateException("SHA-256 unavailable", broken);
        }
    }

    // -----------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------

    private boolean put(String key, byte[] body, String contentType) {
        InstanceRoleCredentials.Credentials temporary = instanceRole.current();
        String keyId = temporary != null ? temporary.accessKeyId() : accessKeyId;
        String secret = temporary != null ? temporary.secretAccessKey() : secretAccessKey;
        String token = temporary != null ? temporary.sessionToken() : sessionToken;

        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            log.error("No credentials for the consent evidence bucket; {} not written", key);
            return false;
        }

        String host = bucket + ".s3." + region + ".amazonaws.com";

        // Object Lock is applied by the bucket's default retention. It is NOT set per request here:
        // a per-object RetainUntilDate would have to be computed by this code, and a clock skew or
        // an arithmetic slip would write an object locked for the wrong span with no way to correct
        // it. Letting the bucket apply its own default keeps one authority for the retention.
        Map<String, String> extra = new HashMap<>();

        // REQUIRED, not an optimisation. A bucket with Object Lock enabled refuses any PUT that
        // carries no integrity check:
        //
        //   400 InvalidRequest - "Content-MD5 OR x-amz-checksum- HTTP header is required for Put
        //                         Object requests with Object Lock parameters"
        //
        // Found by probing the real bucket; every unit test passed without it, because a signature
        // can be perfectly valid and still describe a request S3 will not accept. It would have
        // failed every consent write in production, silently, on a path that swallows its errors.
        //
        // SHA-256 rather than Content-MD5: the digest is already computed for the signature, so
        // this reuses one number instead of introducing a second, weaker one.
        extra.put("x-amz-checksum-sha256", base64Sha256(body));

        try {
            Map<String, String> headers = AwsSigV4.signS3Put(
                    keyId, secret, token, region, host, key, body, contentType, extra, Instant.now());

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + host + "/" + AwsSigV4.encodeS3Key(key)))
                    .timeout(TIMEOUT)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body));

            // "host" is signed but cannot be set on the request: Java's HttpClient reserves it and
            // throws IllegalArgumentException. Same filter as SesEmailSender, for the same reason.
            headers.forEach((name, value) -> {
                if (!"host".equalsIgnoreCase(name)) {
                    request.header(name, value);
                }
            });

            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 == 2) {
                return true;
            }
            // S3 returns XML. Logged whole because the useful part is the <Code> element and it is
            // short; this is an operator-facing error on a path that has already given up.
            log.error("Consent evidence PUT {} failed: HTTP {} {}",
                    key, response.statusCode(), response.body());
            return false;
        } catch (Exception e) {
            log.error("Consent evidence PUT {} failed: {}", key, e.toString());
            return false;
        }
    }
}
