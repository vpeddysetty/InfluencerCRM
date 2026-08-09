package com.influencer.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payouts & Finance: commissions and payouts. Money — strongest isolation.
 *
 * <p>Extracted per docs/EXTRACTION-RUNBOOK.md. Connects as {@code svc_finance}, a role granted
 * only the {@code finance} schema plus insert on the outbox and read on the tenancy spine — so a
 * query against another context's tables fails at the database, not at code review.
 */
@SpringBootApplication
public class InfluencerFinanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluencerFinanceApplication.class, args);
    }
}
