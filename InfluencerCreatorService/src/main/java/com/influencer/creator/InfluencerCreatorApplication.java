package com.influencer.creator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Creator Relationship: creators, interactions, campaign assignments.
 *
 * <p>Extracted per docs/EXTRACTION-RUNBOOK.md. Connects as {@code svc_creator}, a role granted
 * only the {@code creator} schema plus insert on the outbox and read on the tenancy spine — so a
 * query against another context's tables fails at the database, not at code review.
 */
@SpringBootApplication
public class InfluencerCreatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluencerCreatorApplication.class, args);
    }
}
