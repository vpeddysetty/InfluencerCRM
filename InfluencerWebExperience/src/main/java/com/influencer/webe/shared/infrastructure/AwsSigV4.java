package com.influencer.webe.shared.infrastructure;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4, for signing a single JSON POST.
 *
 * <p><b>Why hand-written rather than the AWS SDK.</b> The SDK is the usual answer and would be the
 * right one if it were available — but it is not in this offline build, and pulling
 * {@code software.amazon.awssdk:sesv2} drags in a large transitive tree (Netty, reactive streams,
 * a JSON stack) to make one HTTP call. The same trade was made for Stripe and YouTube: the REST
 * API directly, through the existing client, with no vendor SDK. This is ~100 lines and covers
 * exactly the one request shape SES needs.
 *
 * <p><b>The scope is deliberately narrow.</b> It signs a POST with a JSON body and no query string.
 * A general SigV4 implementation has to canonicalise query parameters, handle unsigned payloads,
 * chunked uploads and pre-signed URLs — each an opportunity to get a security-relevant detail
 * subtly wrong. Refusing to be general is what keeps this auditable.
 *
 * <h2>The three details that make signatures fail</h2>
 *
 * <p><b>Headers must be sorted and lowercased</b>, and the {@code SignedHeaders} list must match
 * exactly what is sent. AWS recomputes the signature from the headers it receives; any divergence
 * produces a {@code SignatureDoesNotMatch} that says nothing about which header was wrong.
 *
 * <p><b>The payload hash covers the exact bytes transmitted.</b> Serialising the body twice — once
 * to hash, once to send — risks a difference in key order or whitespace, and the signature then
 * covers a body that was never sent. This class takes the body as a {@code String} for that reason,
 * the same reason {@code StripeSignature} takes a raw body.
 *
 * <p><b>The timestamp is used in three places</b> and must be identical in all of them: the
 * {@code X-Amz-Date} header, the credential scope, and the string to sign. It is passed in rather
 * than read from the clock so signing is deterministic and therefore testable.
 */
public final class AwsSigV4 {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4() {
    }

    /**
     * Signs a JSON POST and returns the headers to send with it.
     *
     * @param host    e.g. {@code email.us-east-1.amazonaws.com}
     * @param path    e.g. {@code /v2/email/outbound-emails}
     * @param body    the exact JSON string that will be transmitted
     * @return headers including {@code Authorization}, {@code X-Amz-Date} and {@code Host}
     */
    public static Map<String, String> signJsonPost(String accessKeyId,
                                                   String secretAccessKey,
                                                   String sessionToken,
                                                   String region,
                                                   String service,
                                                   String host,
                                                   String path,
                                                   String body,
                                                   Instant now) {

        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String payloadHash = hexSha256(body == null ? "" : body);

        // Sorted, lowercase — the canonical request depends on both, and TreeMap gives the order
        // for free rather than relying on insertion discipline.
        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("content-type", "application/json");
        canonicalHeaders.put("host", host);
        canonicalHeaders.put("x-amz-content-sha256", payloadHash);
        canonicalHeaders.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            // Present only for temporary credentials (an assumed role). It is part of the
            // signature when used, so it cannot simply be added to the request afterwards.
            canonicalHeaders.put("x-amz-security-token", sessionToken);
        }

        StringBuilder headerBlock = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : canonicalHeaders.entrySet()) {
            headerBlock.append(header.getKey()).append(':').append(header.getValue().trim()).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(header.getKey());
        }

        // No query string: this signs a POST only, so the canonical query is empty by construction.
        String canonicalRequest = "POST\n"
                + path + "\n"
                + "\n"
                + headerBlock + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + hexSha256(canonicalRequest);

        byte[] signingKey = signingKey(secretAccessKey, dateStamp, region, service);
        String signature = hex(hmac(signingKey, stringToSign));

        String authorization = ALGORITHM
                + " Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        Map<String, String> headers = new TreeMap<>(canonicalHeaders);
        headers.put("Authorization", authorization);
        return headers;
    }

    /**
     * Signs an S3 PUT of raw bytes and returns the headers to send with it.
     *
     * <p><b>Why this is a sibling of {@link #signJsonPost} and not a generalisation of it.</b> That
     * method hardcodes {@code POST} and {@code content-type: application/json} in the canonical
     * request, and it is the signing path every transactional email already goes through in
     * production. Widening it to take a verb and a content type would put an SES outage one
     * refactor away from an S3 feature, and the two have no reason to share a failure. What is
     * duplicated here is the canonical-request skeleton, which is fixed by the SigV4 spec and does
     * not drift.
     *
     * <p><b>The payload is hashed as bytes.</b> An HTML document is not necessarily UTF-8, and
     * hashing {@code new String(bytes)} would round-trip through a charset and change the digest -
     * producing a signature S3 rejects, and worse, an {@code x-amz-content-sha256} that disagrees
     * with the sha256 recorded as evidence. The evidence hash and the signature hash must be the
     * same number over the same bytes or the record proves nothing.
     *
     * @param key          the object key WITHOUT a leading slash, e.g. {@code receipts/2026/a.json}
     * @param body         the exact bytes that will be transmitted
     * @param contentType  e.g. {@code text/html; charset=utf-8}
     * @param extraHeaders additional {@code x-amz-*} headers to sign, or null. Object Lock is set
     *                     this way; a header sent but not signed is rejected outright.
     * @return headers including {@code Authorization}, {@code X-Amz-Date} and {@code Host}
     */
    public static Map<String, String> signS3Put(String accessKeyId,
                                                String secretAccessKey,
                                                String sessionToken,
                                                String region,
                                                String host,
                                                String key,
                                                byte[] body,
                                                String contentType,
                                                Map<String, String> extraHeaders,
                                                Instant now) {

        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String payloadHash = hexSha256(body == null ? new byte[0] : body);

        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("content-type", contentType);
        canonicalHeaders.put("host", host);
        canonicalHeaders.put("x-amz-content-sha256", payloadHash);
        canonicalHeaders.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            canonicalHeaders.put("x-amz-security-token", sessionToken);
        }
        if (extraHeaders != null) {
            // Lowercased because the canonical request is defined over lowercase header names; a
            // mixed-case entry would sort into the wrong position and break the signature.
            extraHeaders.forEach((name, value) -> canonicalHeaders.put(name.toLowerCase(Locale.ROOT), value));
        }

        StringBuilder headerBlock = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : canonicalHeaders.entrySet()) {
            headerBlock.append(header.getKey()).append(':').append(header.getValue().trim()).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(header.getKey());
        }

        // S3 requires each path SEGMENT encoded, with the separators left alone. URLEncoder is
        // form-encoding, not path-encoding: it turns a space into '+' and escapes '/', both of
        // which produce a key that is not the one asked for.
        String canonicalUri = "/" + encodeS3Key(key);

        String canonicalRequest = "PUT\n"
                + canonicalUri + "\n"
                + "\n"
                + headerBlock + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + hexSha256(canonicalRequest);

        byte[] signingKey = signingKey(secretAccessKey, dateStamp, region, "s3");
        String signature = hex(hmac(signingKey, stringToSign));

        String authorization = ALGORITHM
                + " Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        Map<String, String> headers = new TreeMap<>(canonicalHeaders);
        headers.put("Authorization", authorization);
        return headers;
    }

    /**
     * Signs an S3 HEAD, for reading an object's metadata without fetching it.
     *
     * <p>Used to ask what is already stored under a key before writing over it. The payload is
     * empty, so the content hash is the digest of zero bytes -- the same constant AWS documents as
     * the empty-payload hash, computed here rather than pasted so it cannot be mistyped.
     */
    public static Map<String, String> signS3Head(String accessKeyId,
                                                 String secretAccessKey,
                                                 String sessionToken,
                                                 String region,
                                                 String host,
                                                 String key,
                                                 Map<String, String> extraHeaders,
                                                 Instant now) {

        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String payloadHash = hexSha256(new byte[0]);

        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("host", host);
        canonicalHeaders.put("x-amz-content-sha256", payloadHash);
        canonicalHeaders.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            canonicalHeaders.put("x-amz-security-token", sessionToken);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach((name, value) -> canonicalHeaders.put(name.toLowerCase(Locale.ROOT), value));
        }

        StringBuilder headerBlock = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : canonicalHeaders.entrySet()) {
            headerBlock.append(header.getKey()).append(':').append(header.getValue().trim()).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(header.getKey());
        }

        String canonicalRequest = "HEAD\n"
                + "/" + encodeS3Key(key) + "\n"
                + "\n"
                + headerBlock + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + hexSha256(canonicalRequest);

        byte[] signingKey = signingKey(secretAccessKey, dateStamp, region, "s3");
        String signature = hex(hmac(signingKey, stringToSign));

        Map<String, String> headers = new TreeMap<>(canonicalHeaders);
        headers.put("Authorization", ALGORITHM
                + " Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature);
        return headers;
    }

    /**
     * Percent-encodes an object key segment by segment, leaving {@code /} as a separator.
     *
     * <p>RFC 3986 unreserved characters pass through. Everything else becomes %XX in UPPERCASE,
     * which the canonical request requires - lowercase hex yields a valid-looking signature that
     * S3 rejects.
     *
     * <p>Public because the caller has to build the request URI with the SAME encoding used in the
     * canonical request. Encoding the path one way and signing it another produces a 403 whose
     * message names neither, which is a long afternoon.
     */
    public static String encodeS3Key(String key) {
        StringBuilder out = new StringBuilder(key.length() + 16);
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved || c == '/') {
                out.append(c);
            } else {
                out.append('%')
                   .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                   .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** The four-step derived key. Each step narrows the key's validity: date, region, service. */
    private static byte[] signingKey(String secret, String dateStamp, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception broken) {
            throw new IllegalStateException("Unable to sign an AWS request", broken);
        }
    }

    private static String hexSha256(String value) {
        return hexSha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Digest over raw bytes.
     *
     * <p>Public because the consent evidence writer needs the SAME digest it signs with: the hash
     * stored in Postgres and the hash in {@code x-amz-content-sha256} have to be one number over
     * one sequence of bytes, or the stored value cannot be used to check the stored object.
     */
    public static String hexSha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value == null ? new byte[0] : value));
        } catch (Exception broken) {
            throw new IllegalStateException("SHA-256 unavailable", broken);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
