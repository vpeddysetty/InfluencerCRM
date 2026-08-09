package com.influencer.platform.workload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Set;

/**
 * Mints the workload tokens one service presents to another.
 *
 * <p>Shared rather than reimplemented per service: each copy previously decided independently when
 * to sign, what to do when unconfigured, and where the tenant came from. Those are exactly the
 * decisions that must not vary — a service that quietly issues nothing looks identical, from the
 * outside, to one whose tokens are being rejected.
 *
 * <p><b>Prefers Ed25519, falls back to HMAC, never invents a credential.</b> With a private key it
 * signs asymmetrically. With only the legacy shared secret it signs symmetrically and says so once
 * at startup. With neither it returns null, and the caller sends no header — which lets the
 * receiver apply its own dual-accept policy rather than being handed something forged-looking.
 *
 * <p><b>The tenant and correlation id come from MDC, not from parameters.</b> That sounds indirect,
 * but it is the same tenant the request was already authorized against and it is established once
 * at the edge. Threading it through every call site means the sites that forget mint a token with
 * no tenant — silently reopening the unscoped-access hole the {@code tid} claim exists to close.
 */
public class WorkloadTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(WorkloadTokenIssuer.class);

    private final String issuer;
    private final String privateKey;
    private final String hmacKey;

    public WorkloadTokenIssuer(String issuer, String privateKey, String hmacKey) {
        this.issuer = issuer == null || issuer.isBlank() ? "unknown" : issuer;
        this.privateKey = privateKey;
        this.hmacKey = hmacKey;

        if (hasPrivateKey()) {
            log.info("Workload identity: issuing Ed25519-signed tokens as '{}'", this.issuer);
        } else if (hasHmacKey()) {
            log.warn("Workload identity: issuing HMAC tokens as '{}'. Any holder of this shared "
                    + "key can also FORGE tokens claiming to be this service. Generate a keypair "
                    + "(WorkloadKeyPairGenerator) and set the private key to remove that.",
                    this.issuer);
        } else {
            log.warn("Workload identity is not configured for '{}'. Calls to internal services "
                    + "will carry no workload token.", this.issuer);
        }
    }

    private boolean hasPrivateKey() {
        return privateKey != null && !privateKey.isBlank();
    }

    private boolean hasHmacKey() {
        return hmacKey != null && !hmacKey.isBlank();
    }

    public boolean isConfigured() {
        return hasPrivateKey() || hasHmacKey();
    }

    /** Whether tokens are signed with a key the receiver cannot mint with. */
    public boolean isAsymmetric() {
        return hasPrivateKey();
    }

    public String issueFor(String audience, Set<String> scope) {
        return issueFor(audience, scope, MDC.get("tenant"));
    }

    /** @return the token, or null when nothing is configured */
    public String issueFor(String audience, Set<String> scope, String tenantId) {
        String requestId = MDC.get("rid");
        Instant now = Instant.now();

        if (hasPrivateKey()) {
            return WorkloadToken.issueSigned(
                    issuer, audience, scope, tenantId, requestId, privateKey, now);
        }
        if (hasHmacKey()) {
            return WorkloadToken.issue(
                    issuer, audience, scope, tenantId, requestId, hmacKey, now);
        }
        return null;
    }
}
