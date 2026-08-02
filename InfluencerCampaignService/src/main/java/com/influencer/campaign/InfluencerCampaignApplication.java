package com.influencer.campaign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Campaign Management: campaigns, briefs, spreadsheet import and hydration.
 *
 * <p>Extracted per docs/EXTRACTION-RUNBOOK.md. Connects as {@code svc_campaign}, a role granted
 * only the {@code campaign} schema plus insert on the outbox and read on the tenancy spine — so a
 * query against another context's tables fails at the database, not at code review.
 */
@SpringBootApplication
public class InfluencerCampaignApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluencerCampaignApplication.class, args);
    }
}
