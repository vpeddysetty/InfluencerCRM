package com.influencer.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Identity & Access: accounts, brands, memberships, users, refresh tokens. The tenancy spine every other context reads.
 *
 * <p>Extracted per docs/EXTRACTION-RUNBOOK.md. Connects as {@code svc_identity}, a role granted
 * only the {@code identity} schema plus insert on the outbox and read on the tenancy spine — so a
 * query against another context's tables fails at the database, not at code review.
 */
@SpringBootApplication
public class InfluencerIdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluencerIdentityApplication.class, args);
    }
}
