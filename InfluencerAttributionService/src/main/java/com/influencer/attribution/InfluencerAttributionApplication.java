package com.influencer.attribution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Attribution & Commerce: campaign codes, sale attributions, marketplace connections, daily stats. Highest write volume.
 *
 * <p>Extracted per docs/EXTRACTION-RUNBOOK.md. Connects as {@code svc_attribution}, a role granted
 * only the {@code attribution} schema plus insert on the outbox and read on the tenancy spine — so a
 * query against another context's tables fails at the database, not at code review.
 */
@SpringBootApplication
public class InfluencerAttributionApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluencerAttributionApplication.class, args);
    }
}
