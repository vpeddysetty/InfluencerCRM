package com.influencer.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Content & Landing: templates and public page views. Different caching profile.
 *
 * <p>Extracted per docs/EXTRACTION-RUNBOOK.md. Connects as {@code svc_content}, a role granted
 * only the {@code content} schema plus insert on the outbox and read on the tenancy spine — so a
 * query against another context's tables fails at the database, not at code review.
 */
@SpringBootApplication
public class InfluencerContentApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluencerContentApplication.class, args);
    }
}
