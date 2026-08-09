package com.influencer.dps.workload;

import com.influencer.platform.workload.WorkloadTokenIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The DPS's workload identity.
 *
 * <p>Issue-only: nothing calls the DPS service-to-service — the browser reaches it with a cookie,
 * which is a different credential entirely. Adding a verifier here would be dead code implying a
 * trust relationship that does not exist.
 */
@Configuration
public class WorkloadConfig {

    @Bean
    public WorkloadTokenIssuer workloadTokenIssuer(
            @Value("${dps.workload.private-key:}") String privateKey,
            @Value("${dps.workload.signing-key:}") String hmacKey) {
        return new WorkloadTokenIssuer("dps", privateKey, hmacKey);
    }
}
