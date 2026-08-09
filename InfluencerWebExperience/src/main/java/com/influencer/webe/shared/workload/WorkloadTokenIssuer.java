package com.influencer.webe.shared.workload;

import com.influencer.webe.shared.observability.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Mints the workload tokens this service presents to the ones it calls (roadmap step 3).
 *
 * <p><b>Symmetric to start, asymmetric later.</b> A shared HMAC key means the DAO can verify
 * without a key-distribution service, which is what makes this deployable today. It is still a
 * decisive improvement on the static token it replaces — tokens expire in minutes, name their
 * issuer, name their audience, and carry a signed tenant — but it is not the end state: any holder
 * of the key can also *mint*. Moving to per-service signing keys with public verification is the
 * next step, and only this class and the verifier change.
 *
 * <p><b>Unconfigured means "issue nothing", never "fall back to the old token".</b> Returning null
 * here lets the caller keep sending the legacy header during the dual-accept window; what it must
 * not do is invent a token or silently claim success. The lesson is the one from M3.1: a fallback
 * that looks like it worked is how a security control ends up switched off in exactly one
 * environment.
 */
@Component
public class WorkloadTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(WorkloadTokenIssuer.class);

    private final String signingKey;
    private final String issuer;

    public WorkloadTokenIssuer(
            @Value("${web-experience.workload.signing-key:}") String signingKey,
            @Value("${spring.application.name:web-experience}") String issuer) {
        this.signingKey = signingKey;
        this.issuer = issuer;

        if (signingKey == null || signingKey.isBlank()) {
            log.warn("No workload signing key configured. Calls to internal services will use the "
                    + "legacy shared service token only. Set web-experience.workload.signing-key "
                    + "(and dao.workload.signing-key to the same value) to enable per-request "
                    + "workload identity.");
        } else {
            log.info("Workload identity enabled for issuer '{}'", issuer);
        }
    }

    public boolean isConfigured() {
        return signingKey != null && !signingKey.isBlank();
    }

    /**
     * Issues a token for one outbound call.
     *
     * <p>The tenant comes from the logging context rather than a parameter. That sounds indirect,
     * but it is the same value the request was authorized against and it is already established at
     * the edge — passing it explicitly would mean threading it through every DAO call site, and the
     * ones that forgot would silently mint a token with no tenant, which is precisely the
     * unscoped-access problem this exists to close.
     *
     * @return the token, or null when no key is configured
     */
    public String issueFor(String audience, Set<String> scope) {
        if (!isConfigured()) {
            return null;
        }
        return WorkloadToken.issue(
                issuer,
                audience,
                scope,
                LogContext.get(LogContext.TENANT),
                LogContext.requestId(),
                signingKey,
                Instant.now());
    }
}
