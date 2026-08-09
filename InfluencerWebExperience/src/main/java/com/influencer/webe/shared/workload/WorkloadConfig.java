package com.influencer.webe.shared.workload;

import com.influencer.platform.workload.WorkloadTokenIssuer;
import com.influencer.platform.workload.WorkloadTokenVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires this service's workload identity: the key it signs with, and the issuers it trusts.
 *
 * <p>Every value is optional and defaults to empty. Configuring nothing leaves the previous
 * behaviour exactly as it was — the shared service token — so this can be deployed before any key
 * material exists, which is what makes the migration incremental rather than a flag day.
 */
@Configuration
public class WorkloadConfig {

    /** Signs outbound calls (to the DAO). */
    @Bean
    public WorkloadTokenIssuer workloadTokenIssuer(
            @Value("${spring.application.name:web-experience}") String issuer,
            @Value("${web-experience.workload.private-key:}") String privateKey,
            @Value("${web-experience.workload.signing-key:}") String hmacKey) {
        return new WorkloadTokenIssuer(issuer, privateKey, hmacKey);
    }

    /**
     * Verifies inbound calls addressed to {@code bff}.
     *
     * <p>Only the DPS is trusted today. Adding an issuer here is deliberately a code-and-config
     * act: a service that can call this one is a trust decision, and it should appear in a diff.
     */
    @Bean
    public WorkloadTokenVerifier workloadTokenVerifier(
            @Value("${web-experience.workload.trust.dps:}") String dpsPublicKey,
            @Value("${web-experience.workload.dps-key:}") String legacyDpsHmac) {
        Map<String, String> trusted = new LinkedHashMap<>();
        trusted.put("dps", dpsPublicKey);
        return new WorkloadTokenVerifier("bff", trusted, legacyDpsHmac);
    }
}
