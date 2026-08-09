package com.influencer.platform.workload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies workload tokens presented to this service, against the public keys of the issuers it
 * trusts.
 *
 * <p><b>A map of issuer → public key, not one key.</b> The point of asymmetric signing is that each
 * service has its own identity; a single shared verification key would collapse them back into one
 * indistinguishable principal. Trust is therefore explicit — the DAO trusts {@code web-experience},
 * and adding a new caller means adding its public key, which is a reviewable act rather than a
 * side effect of sharing a secret.
 *
 * <p><b>The issuer is taken from the verified claims, never from the token before verification.</b>
 * Selecting a key by an unverified {@code iss} would let a caller nominate which key checks its
 * own signature. So every trusted key is tried; a token verifies if any of them proves it, and the
 * issuer is only believed afterwards. With a handful of services that is a few signature checks,
 * which is not a hot path concern next to the network hop it authorizes.
 *
 * <p><b>Legacy HMAC is accepted only while a key is configured</b>, so removing the old secret is
 * what completes the migration — and until then {@link Result#legacy()} marks each acceptance so
 * the caller can log it and make that removal evidence-based.
 */
public class WorkloadTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(WorkloadTokenVerifier.class);

    private final String audience;
    private final Map<String, String> trustedPublicKeys;
    private final String legacyHmacKey;

    /**
     * @param audience          this service's name, as callers must have addressed it
     * @param trustedPublicKeys issuer name → base64 X.509 public key
     * @param legacyHmacKey     the shared secret; null or blank once migration is complete
     */
    public WorkloadTokenVerifier(String audience,
                                 Map<String, String> trustedPublicKeys,
                                 String legacyHmacKey) {
        this.audience = audience;
        this.trustedPublicKeys = new LinkedHashMap<>();
        if (trustedPublicKeys != null) {
            trustedPublicKeys.forEach((issuer, key) -> {
                if (key != null && !key.isBlank()) {
                    this.trustedPublicKeys.put(issuer, key.trim());
                }
            });
        }
        this.legacyHmacKey = legacyHmacKey;

        if (this.trustedPublicKeys.isEmpty() && (legacyHmacKey == null || legacyHmacKey.isBlank())) {
            log.warn("No workload verification keys configured for audience '{}'. Every workload "
                    + "token will be refused.", audience);
        } else {
            log.info("Workload verification for '{}': {} trusted issuer(s){}", audience,
                    this.trustedPublicKeys.size(),
                    (legacyHmacKey == null || legacyHmacKey.isBlank())
                            ? "" : ", legacy HMAC still accepted");
        }
    }

    /** The outcome of a verification, distinguishing how the token was proved. */
    public record Result(WorkloadToken.Claims claims, boolean legacy) {
        public boolean valid() {
            return claims != null;
        }
    }

    /**
     * @return a result whose claims are null if the token is absent, malformed, expired, addressed
     *     elsewhere, or signed by nobody trusted
     */
    public Result verify(String token) {
        if (token == null || token.isBlank()) {
            return new Result(null, false);
        }

        for (String publicKey : trustedPublicKeys.values()) {
            WorkloadToken.Claims claims =
                    WorkloadToken.verifySigned(token, audience, publicKey, Instant.now());
            if (claims != null) {
                return new Result(claims, false);
            }
        }

        if (legacyHmacKey != null && !legacyHmacKey.isBlank()) {
            WorkloadToken.Claims claims =
                    WorkloadToken.verify(token, audience, null, legacyHmacKey, Instant.now());
            if (claims != null) {
                return new Result(claims, true);
            }
        }

        return new Result(null, false);
    }

    public boolean isConfigured() {
        return !trustedPublicKeys.isEmpty() || (legacyHmacKey != null && !legacyHmacKey.isBlank());
    }
}
