package com.influencer.webe.billing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

/**
 * Verifies Stripe's webhook signature (roadmap M2.2).
 *
 * <p><b>Why this is not optional.</b> The webhook endpoint cannot require a user token — Stripe
 * holds none — so the signature <em>is</em> the authentication. Without it, anyone who knows the
 * URL can POST {@code subscription.updated} and move their own account onto the agency plan for
 * free. It was left unimplemented until a real provider existed precisely because a stub returning
 * true would have looked finished while leaving that door open.
 *
 * <p><b>The raw body matters.</b> The signature covers the exact bytes Stripe sent. A body parsed
 * into a {@code JsonNode} and re-serialised will differ — key order, whitespace, number
 * formatting — and will never verify. That is why the controller takes a {@code String} rather
 * than a parsed object.
 *
 * <p><b>Constant-time comparison.</b> {@link MessageDigest#isEqual} rather than
 * {@code String.equals}: an early-exit comparison leaks, through timing, how many leading bytes
 * were right, which is enough to forge a signature one byte at a time.
 *
 * <p><b>Timestamp tolerance.</b> A valid signature stays valid forever, so without a freshness
 * check a captured request could be replayed indefinitely. Five minutes matches Stripe's own
 * recommendation and tolerates ordinary clock drift.
 */
public final class StripeSignature {

    /** Stripe's recommended tolerance. Beyond this a correctly-signed request is refused as stale. */
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private StripeSignature() {
    }

    /**
     * Whether {@code header} is a signature Stripe could have produced for {@code rawBody}.
     *
     * @param header  the {@code Stripe-Signature} header: {@code t=<unix>,v1=<hex>[,v1=<hex>]}
     * @param rawBody the EXACT request body bytes, as received
     * @param secret  the endpoint's signing secret ({@code whsec_…})
     * @param now     current time, injected so the tolerance window is testable
     */
    public static boolean verify(String header, String rawBody, String secret, Instant now) {
        if (header == null || header.isBlank() || rawBody == null
                || secret == null || secret.isBlank()) {
            return false;
        }

        long timestamp = -1;
        boolean matched = false;

        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            String name = pair[0].trim();
            String value = pair[1].trim();

            if ("t".equals(name)) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if ("v1".equals(name)) {
                // Every v1 is checked rather than stopping at the first. During a secret rotation
                // Stripe sends one per active secret, and short-circuiting would reject requests
                // signed with the other valid one.
                //
                // Deliberately no `break` on a match: leaving the loop early would make the work
                // done depend on which candidate matched, reintroducing a timing signal.
                matched |= matches(value, rawBody, secret, timestamp);
            }
        }

        if (timestamp < 0 || !matched) {
            return false;
        }
        return isFresh(timestamp, now);
    }

    /** Recomputes the HMAC over Stripe's signed payload: {@code <timestamp>.<raw body>}. */
    private static boolean matches(String candidate, String rawBody, String secret, long timestamp) {
        if (timestamp < 0) {
            return false;
        }
        String signedPayload = timestamp + "." + rawBody;
        String expected = hmacSha256Hex(signedPayload, secret);
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether the signature is recent enough.
     *
     * <p>Guards both directions. A far-future timestamp is refused too: accepting one would let a
     * captured request be held and replayed at will.
     */
    static boolean isFresh(long timestamp, Instant now) {
        Instant signedAt = Instant.ofEpochSecond(timestamp);
        Duration age = Duration.between(signedAt, now == null ? Instant.now() : now);
        return age.abs().compareTo(TOLERANCE) <= 0;
    }

    static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // A missing HmacSHA256 would mean a broken JVM. Refusing to verify is the only safe
            // answer; claiming success here would disable the check entirely.
            return null;
        }
    }
}
