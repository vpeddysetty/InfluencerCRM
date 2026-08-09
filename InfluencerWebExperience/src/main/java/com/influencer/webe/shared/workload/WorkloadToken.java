package com.influencer.webe.shared.workload;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A short-lived, audience-scoped credential for one service calling another (roadmap step 3).
 *
 * <p><b>What it replaces.</b> The DAO accepted a single static {@code X-Service-Token} — one string,
 * shared by every caller, that never expired, identified nobody (the filter hard-coded the
 * principal as {@code "web-experience"}), and authorized everything. Anyone holding it could read
 * or write any table for any tenant, and because {@code brandId} is an optional query parameter,
 * omitting it returned every tenant's rows. Rotating it meant changing one value everywhere at
 * once, which is why it never was.
 *
 * <p><b>The five claims and what each one buys.</b>
 * <ul>
 *   <li>{@code exp} — minutes, not forever. A leaked token is dead before it is useful, and
 *       rotation stops being a synchronised outage.</li>
 *   <li>{@code aud} — a token minted for the DAO cannot be replayed against the BFF. Without an
 *       audience, one capture works everywhere the same key is trusted.</li>
 *   <li>{@code iss} — audit lines name the real caller instead of a hard-coded literal.</li>
 *   <li>{@code tid} — the tenant, <em>signed</em>. This is the one that closes the IDOR: the DAO
 *       can derive the tenant from the credential rather than trusting a query parameter that a
 *       caller may simply omit.</li>
 *   <li>{@code rid} — the correlation id, so an authorization decision can be joined to the browser
 *       action that caused it.</li>
 * </ul>
 *
 * <p><b>Format.</b> {@code v1.<base64url(claims)>.<base64url(hmac)>} — JWT-shaped but deliberately
 * not a JWT: no library is available offline here, and a hand-rolled JWT parser is a well-known way
 * to reintroduce {@code alg: none}. This format has exactly one algorithm and no negotiation, so
 * there is nothing to downgrade.
 *
 * <p><b>Signature covers the encoded claims exactly as transmitted.</b> Verifying against a
 * re-serialised copy would let a difference in key order or spacing change the payload while the
 * signature still matched.
 */
public final class WorkloadToken {

    public static final String HEADER = "X-Workload-Token";

    /** Long enough for a slow call and modest clock skew; short enough that a leak expires fast. */
    public static final Duration TTL = Duration.ofMinutes(5);

    /** Tolerance for clocks that disagree between hosts. */
    private static final Duration SKEW = Duration.ofSeconds(30);

    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private WorkloadToken() {
    }

    /** The verified content of a token. */
    public record Claims(String issuer,
                         String audience,
                         Set<String> scope,
                         String tenantId,
                         String requestId,
                         Instant expiresAt) {

        public boolean hasScope(String required) {
            return scope.contains(required);
        }
    }

    /**
     * Mints a token.
     *
     * @param audience the service that may accept it, e.g. {@code dao}
     * @param scope    what the caller intends to do; the receiver decides whether it is enough
     * @param tenantId the tenant being acted on, or null for a call that is not tenant-scoped
     */
    public static String issue(String issuer,
                               String audience,
                               Set<String> scope,
                               String tenantId,
                               String requestId,
                               String signingKey,
                               Instant now) {

        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        // Sorted so the encoding is deterministic — it makes a token diffable in a log and removes
        // set-ordering as a variable when comparing two tokens during an investigation.
        claims.put("scp", String.join(" ", new TreeSet<>(scope == null ? Set.of() : scope)));
        claims.put("tid", tenantId == null ? "" : tenantId);
        claims.put("rid", requestId == null ? "" : requestId);
        claims.put("exp", Long.toString(now.plus(TTL).getEpochSecond()));

        String payload = ENCODER.encodeToString(serialize(claims).getBytes(StandardCharsets.UTF_8));
        String signature = sign(VERSION + "." + payload, signingKey);
        return VERSION + "." + payload + "." + signature;
    }

    /**
     * Verifies a token and returns its claims, or null if it is not acceptable for {@code audience}.
     *
     * <p>Returns null rather than throwing for every rejection reason. A caller must not be able to
     * tell "bad signature" from "wrong audience" from "expired" — the distinctions are useful to
     * someone probing and to nobody else. The reason is logged server-side instead.
     */
    public static Claims verify(String token, String expectedAudience, String signingKey, Instant now) {
        if (token == null || token.isBlank() || signingKey == null || signingKey.isBlank()) {
            return null;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return null;
        }

        String expected = sign(parts[0] + "." + parts[1], signingKey);
        // Constant-time: an early-exit compare leaks, through timing, how many leading bytes were
        // right, which is enough to forge a signature one byte at a time.
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }

        Map<String, String> claims;
        try {
            claims = deserialize(new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception malformed) {
            return null;
        }

        if (!expectedAudience.equals(claims.get("aud"))) {
            return null;
        }

        long exp;
        try {
            exp = Long.parseLong(claims.getOrDefault("exp", "0"));
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (Instant.ofEpochSecond(exp).plus(SKEW).isBefore(now)) {
            return null;
        }

        String rawScope = claims.getOrDefault("scp", "");
        Set<String> scope = rawScope.isBlank() ? Set.of() : Set.of(rawScope.split(" "));

        return new Claims(
                claims.getOrDefault("iss", ""),
                claims.getOrDefault("aud", ""),
                scope,
                emptyToNull(claims.get("tid")),
                emptyToNull(claims.get("rid")),
                Instant.ofEpochSecond(exp));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * A minimal {@code k=v} encoding, newline-separated.
     *
     * <p>Values are restricted at the point of issue to ids, scopes and digits — none of which can
     * contain a delimiter — so this needs no escaping. Deliberately not JSON: pulling a parser into
     * the verification path of a security credential adds attack surface for no benefit at this
     * size.
     */
    private static String serialize(Map<String, String> claims) {
        StringBuilder out = new StringBuilder(128);
        for (Map.Entry<String, String> claim : claims.entrySet()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(claim.getKey()).append('=').append(sanitize(claim.getValue()));
        }
        return out.toString();
    }

    private static Map<String, String> deserialize(String raw) {
        Map<String, String> claims = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                claims.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return claims;
    }

    /** Strips anything that could confuse the encoding. Applied at issue, so verification is total. */
    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[\\n\\r=]", "");
    }

    private static String sign(String payload, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception broken) {
            // A missing HmacSHA256 means a broken JVM. Returning a value that cannot match is the
            // only safe answer; claiming success would disable verification entirely.
            throw new IllegalStateException("Unable to sign workload token", broken);
        }
    }
}
