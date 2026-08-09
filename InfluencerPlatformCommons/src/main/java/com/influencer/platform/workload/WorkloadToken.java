package com.influencer.platform.workload;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A short-lived, audience-scoped credential for one service calling another.
 *
 * <p><b>What it replaces.</b> The DAO accepted a single static {@code X-Service-Token} — one string,
 * shared by every caller, that never expired, identified nobody (the filter hard-coded the
 * principal as {@code "web-experience"}), and authorized everything. Rotating it meant changing one
 * value everywhere at once, which is why it never was.
 *
 * <p><b>The five claims and what each one buys.</b>
 * <ul>
 *   <li>{@code exp} — minutes, not forever. A leaked token is dead before it is useful, and
 *       rotation stops being a synchronised outage.</li>
 *   <li>{@code aud} — a token minted for the DAO cannot be replayed against the BFF. Without an
 *       audience, one capture works everywhere the same key is trusted.</li>
 *   <li>{@code iss} — audit lines name the real caller instead of a hard-coded literal.</li>
 *   <li>{@code tid} — the tenant, <em>signed</em>. The DAO derives the tenant from the credential
 *       rather than trusting a query parameter a caller may simply omit.</li>
 *   <li>{@code rid} — the correlation id, joining an authorization decision to the browser action
 *       that caused it.</li>
 * </ul>
 *
 * <h2>Two signature schemes, and why both exist</h2>
 *
 * <p><b>{@code v2} — Ed25519, asymmetric. Prefer this.</b> The verifier holds only a public key, so
 * compromising the DAO yields no ability to <em>mint</em> a token. Under the symmetric scheme every
 * verifier is also a forger: an attacker reading the DAO's config could mint tokens claiming to be
 * the BFF, for any tenant. That is the substantive difference, and it is why per-service keys are
 * the target state rather than a refinement.
 *
 * <p>Ed25519 rather than RSA: 64-byte signatures against RSA-2048's 256, no parameter choices to
 * get wrong, and it is in the JDK from 15 so it needs no dependency — which matters because this
 * build is offline.
 *
 * <p><b>{@code v1} — HMAC, symmetric. Still verified, deliberately.</b> Removing it would mean
 * every service must deploy in the same instant, on the hop every request crosses. Verification
 * accepts both; issuance prefers v2 whenever a private key is configured. When no v1 tokens have
 * been seen for a full deploy cycle, delete {@link #verifyHmac} and the key plumbing behind it.
 *
 * <p><b>The version is read from the token but never negotiated.</b> Each version maps to exactly
 * one algorithm, chosen here — a token cannot ask to be verified differently, which is the
 * {@code alg: none} class of bug that makes hand-rolled JWT parsing dangerous. A v2 token is
 * <em>only</em> ever checked with Ed25519 and a v1 token <em>only</em> with HMAC.
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

    /** Symmetric HMAC. Legacy — verified, no longer preferred for issuance. */
    static final String V1 = "v1";

    /** Asymmetric Ed25519. The verifier cannot mint. */
    static final String V2 = "v2";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private WorkloadToken() {
    }

    /** The verified content of a token, plus which scheme proved it. */
    public record Claims(String issuer,
                         String audience,
                         Set<String> scope,
                         String tenantId,
                         String requestId,
                         Instant expiresAt,
                         String scheme) {

        public boolean hasScope(String required) {
            return scope.contains(required);
        }

        /** Whether this was proved by a key the verifier could not have minted with. */
        public boolean isAsymmetric() {
            return V2.equals(scheme);
        }
    }

    // ---- issuing --------------------------------------------------------

    /**
     * Mints an Ed25519-signed token. The preferred path.
     *
     * @param privateKey the issuer's PKCS#8 private key, base64. Never leaves the issuing service.
     */
    public static String issueSigned(String issuer,
                                     String audience,
                                     Set<String> scope,
                                     String tenantId,
                                     String requestId,
                                     String privateKey,
                                     Instant now) {
        String payload = encodeClaims(issuer, audience, scope, tenantId, requestId, now);
        String signingInput = V2 + "." + payload;
        return signingInput + "." + signEd25519(signingInput, privateKey);
    }

    /**
     * Mints an HMAC-signed token.
     *
     * @deprecated use {@link #issueSigned}. Retained so a service without a keypair configured can
     *     still call one that has not yet been migrated; any holder of this key can also forge.
     */
    @Deprecated
    public static String issue(String issuer,
                               String audience,
                               Set<String> scope,
                               String tenantId,
                               String requestId,
                               String signingKey,
                               Instant now) {
        String payload = encodeClaims(issuer, audience, scope, tenantId, requestId, now);
        String signingInput = V1 + "." + payload;
        return signingInput + "." + hmac(signingInput, signingKey);
    }

    private static String encodeClaims(String issuer,
                                       String audience,
                                       Set<String> scope,
                                       String tenantId,
                                       String requestId,
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
        return ENCODER.encodeToString(serialize(claims).getBytes(StandardCharsets.UTF_8));
    }

    // ---- verifying ------------------------------------------------------

    /**
     * Verifies a token of either scheme.
     *
     * <p>The version in the token selects which key is used, but never <em>whether</em> a check
     * happens: a v2 token is only ever checked with Ed25519 and a v1 only with HMAC. Passing null
     * for a key simply makes tokens of that scheme unverifiable, which is the correct behaviour for
     * a service that has finished migrating.
     *
     * <p>Returns null rather than throwing for every rejection reason. A caller must not be able to
     * distinguish "bad signature" from "wrong audience" from "expired" — those distinctions are
     * useful to someone probing and to nobody else. The reason is logged server-side instead.
     *
     * @param publicKey the issuer's X.509 public key, base64; null disables v2
     * @param hmacKey   the legacy shared secret; null disables v1
     */
    public static Claims verify(String token,
                                String expectedAudience,
                                String publicKey,
                                String hmacKey,
                                Instant now) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        String version = parts[0];
        String signingInput = parts[0] + "." + parts[1];

        boolean signatureValid;
        if (V2.equals(version)) {
            signatureValid = verifyEd25519(signingInput, parts[2], publicKey);
        } else if (V1.equals(version)) {
            signatureValid = verifyHmac(signingInput, parts[2], hmacKey);
        } else {
            // An unknown version is refused rather than defaulted. Defaulting to the weaker scheme
            // would let a caller pick its own algorithm by inventing a prefix.
            return null;
        }

        if (!signatureValid) {
            return null;
        }
        return decodeClaims(parts[1], expectedAudience, version, now);
    }

    /** Convenience for a service that has fully migrated: v2 only. */
    public static Claims verifySigned(String token,
                                      String expectedAudience,
                                      String publicKey,
                                      Instant now) {
        return verify(token, expectedAudience, publicKey, null, now);
    }

    private static Claims decodeClaims(String encodedPayload,
                                       String expectedAudience,
                                       String scheme,
                                       Instant now) {
        Map<String, String> claims;
        try {
            claims = deserialize(new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8));
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
                Instant.ofEpochSecond(exp),
                scheme);
    }

    // ---- crypto ---------------------------------------------------------

    private static String signEd25519(String payload, String privateKeyBase64) {
        if (privateKeyBase64 == null || privateKeyBase64.isBlank()) {
            throw new IllegalStateException("No workload private key configured");
        }
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.trim())));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(signature.sign());
        } catch (Exception failure) {
            // Never degrade to an unsigned or HMAC token here — a caller asked for the strong
            // scheme, and silently returning a weaker credential is how a downgrade goes unnoticed.
            throw new IllegalStateException("Unable to sign workload token", failure);
        }
    }

    private static boolean verifyEd25519(String payload, String candidate, String publicKeyBase64) {
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            return false;
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.verify(DECODER.decode(candidate));
        } catch (Exception malformed) {
            // A malformed signature or key is a rejection, not an error to propagate: this runs on
            // attacker-supplied input, and throwing would turn a bad request into a 500.
            return false;
        }
    }

    private static boolean verifyHmac(String payload, String candidate, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String expected = hmac(payload, key);
        // Constant-time: an early-exit compare leaks, through timing, how many leading bytes were
        // right, which is enough to forge a signature one byte at a time.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String payload, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception broken) {
            throw new IllegalStateException("Unable to sign workload token", broken);
        }
    }

    // ---- encoding -------------------------------------------------------

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
}
