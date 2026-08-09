package com.influencer.dps.workload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Mints the DPS's own workload token for calls to the BFF (roadmap step 4).
 *
 * <p><b>What this adds on top of the user's bearer token.</b> The bearer says who the request is
 * <em>for</em>; it says nothing about who is <em>asking</em>. Any process holding a valid user
 * token could previously call the BFF and be indistinguishable from the DPS relaying that user's
 * click. With this header the BFF can tell the two apart, which is the difference between trusting
 * a token and trusting a caller — the substance of "zero trust" at this hop.
 *
 * <p>Unconfigured issues nothing rather than inventing a token: the BFF treats the header as
 * optional today, so an absent key degrades to current behaviour instead of failing closed on a
 * hop that every request crosses.
 */
@Component
public class WorkloadTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(WorkloadTokenIssuer.class);

    private final String signingKey;

    public WorkloadTokenIssuer(@Value("${dps.workload.signing-key:}") String signingKey) {
        this.signingKey = signingKey;
        if (signingKey == null || signingKey.isBlank()) {
            log.warn("No DPS workload signing key configured. Calls to the BFF will carry the "
                    + "user's bearer token only, so the BFF cannot verify which service relayed "
                    + "them. Set dps.workload.signing-key and web-experience.workload.dps-key to "
                    + "the same value.");
        }
    }

    public boolean isConfigured() {
        return signingKey != null && !signingKey.isBlank();
    }

    /** @return a token for {@code audience}, or null when no key is configured */
    public String issueFor(String audience, Set<String> scope, String tenantId) {
        if (!isConfigured()) {
            return null;
        }
        return WorkloadToken.issue(
                "dps", audience, scope, tenantId, MDC.get("rid"), signingKey, Instant.now());
    }
}
