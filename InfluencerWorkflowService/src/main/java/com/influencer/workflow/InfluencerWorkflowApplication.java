package com.influencer.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Collaboration Workflow context, running as its own service.
 *
 * <p>First extraction under docs/EXTRACTION-RUNBOOK.md. Workflow was chosen over Identity — despite
 * Identity being first in dependency order — because it has three tables, no money and no inbound
 * ports, making it the cheapest place to find out what the runbook got wrong.
 *
 * <p>Connects as {@code svc_workflow}, a role granted only the {@code workflow} schema plus insert
 * on the outbox and read on the tenancy spine. A query against another context's tables fails at the
 * database, not at code review.
 */
@SpringBootApplication
public class InfluencerWorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluencerWorkflowApplication.class, args);
    }
}
