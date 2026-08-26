package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.workload.CrossTenantRead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes pages whose scheduled time has arrived (roadmap PR-35, screen 6).
 *
 * <p><b>It goes through {@link LandingStageService#publishNow} rather than writing status.</b>
 * That path is where the empty-page guard, the stage machine, the transition audit row, the
 * workflow-board sync and the hosting-window clock all live. A scheduler that set
 * {@code status='published'} directly would publish blank pages, skip the audit trail, leave the
 * board showing the old column, and never start the free-hosting clock — four bugs that would each
 * look like something else.
 *
 * <p><b>Late is correct; early is not.</b> The sweep asks "which pages are due?", not "which are
 * due right now", so a run missed to a deploy or an outage publishes on the next run rather than
 * being skipped. That makes the job self-healing in the one direction that is safe: a page going
 * live a few minutes late is a small problem, and one going live early is a broken embargo.
 *
 * <p><b>Clearing the time is what makes this run-once.</b> The column is set to null as part of
 * publishing, so a second sweep finds nothing — the same "record what is still owed, not what
 * happened" reasoning as the expiry warnings' threshold marker.
 *
 * <p><b>Disabled by default</b>, like {@code HostingExpiryScheduler} and {@code DomainEventRelay}.
 * A job that publishes customer pages the moment anyone runs the application — including from a
 * developer's machine pointed at a shared database — is a bad surprise.
 *
 * <p><b>OP-17 applies:</b> this is plain {@code @Scheduled} with no ShedLock, so a second instance
 * would double-publish. Publishing twice is far less harmful than double-sending email (the second
 * transition is a no-op once the stage is already {@code published}), but the same multi-instance
 * prerequisite named in §7.2 of the roadmap holds before the HA flag is turned on.
 */
@Component
@ConditionalOnProperty(name = "web-experience.landing.scheduled-publish.enabled", havingValue = "true")
public class ScheduledPublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPublishScheduler.class);

    /** Marks the transition's origin in the audit trail, distinct from a person pressing publish. */
    static final String SOURCE = "scheduler";

    private final DaoGatewayClient dao;
    private final LandingStageService stages;

    public ScheduledPublishScheduler(DaoGatewayClient dao, LandingStageService stages) {
        this.dao = dao;
        this.stages = stages;
    }

    /**
     * Publishes every page whose time has passed.
     *
     * <p>Every minute by default. The granularity the UI offers is minutes, so sweeping more often
     * spends DAO calls to gain nothing a user could perceive.
     */
    @Scheduled(
            initialDelayString = "${web-experience.landing.scheduled-publish.initial-delay-ms:60000}",
            fixedDelayString = "${web-experience.landing.scheduled-publish.interval-ms:60000}")
    public void publishDuePages() {
        // This job has no user and no brand — it publishes across every tenant — so it proves that
        // permission with an explicit scope rather than relying on the DAO permitting unscoped
        // reads. Wrapped so the flag cannot outlive the sweep on a pooled scheduler thread.
        CrossTenantRead.runAsSweep(this::sweep);
    }

    private void sweep() {
        int published = 0;
        int failed = 0;
        try {
            JsonNode pages = dao.get("/landing-templates", Map.of());
            if (pages == null || !pages.isArray()) {
                return;
            }
            Instant now = Instant.now();
            for (JsonNode page : pages) {
                if (!isDue(page, now)) {
                    continue;
                }
                try {
                    publish(page);
                    published++;
                } catch (RuntimeException e) {
                    // One page's failure must not abort the sweep. A batch job that stops on the
                    // first bad row silently strands every page behind it — and the page that
                    // failed keeps its scheduled time, so the next run retries it.
                    failed++;
                    log.warn("Scheduled publish failed for page {}: {}",
                            page.path("id").asText("?"), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Scheduled publish sweep could not run: {}", e.toString());
        }
        if (published > 0 || failed > 0) {
            log.info("Scheduled publish: {} page(s) published, {} failed", published, failed);
        }
    }

    /** A page is due when it carries a time that has passed. */
    private boolean isDue(JsonNode page, Instant now) {
        if (page == null || !page.hasNonNull("scheduledPublishAt")) {
            return false;
        }
        try {
            // !isAfter rather than isBefore: a page scheduled for exactly now is due. isBefore
            // would leave it for the next sweep, which is a minute of unexplained delay.
            return !Instant.parse(page.get("scheduledPublishAt").asText()).isAfter(now);
        } catch (RuntimeException e) {
            // An unparseable timestamp would otherwise be retried every minute forever.
            log.warn("Page {} has an unreadable scheduledPublishAt and will be skipped",
                    page.path("id").asText("?"));
            return false;
        }
    }

    private void publish(JsonNode page) {
        UUID brandId = UUID.fromString(page.get("brandId").asText());
        UUID templateId = UUID.fromString(page.get("id").asText());

        // Idempotency key ties the transition row to the scheduled instant rather than to the run,
        // so a retry after a partial failure recognises the work as already done instead of
        // writing a second transition for the same scheduled publish.
        String key = templateId + ":scheduled:" + page.get("scheduledPublishAt").asText();
        // publishNow, not changeStage(..., PUBLISHED): the stage machine has no direct
        // draft -> published edge, so a page scheduled while still in `draft` — which is most of
        // them, since scheduling is what someone does INSTEAD of walking the review stages — was
        // refused 409 on every sweep, forever. It logged a warning nobody was reading and the page
        // never went live. publishNow walks the shortest legal path, so each hop is still
        // validated and audited.
        stages.publishNow(brandId, templateId, SOURCE, key);

        // Cleared only AFTER the publish succeeds. Clearing first would lose the schedule if the
        // transition then failed, turning a retryable problem into a page that never publishes and
        // no longer remembers that it should have.
        stages.clearSchedule(brandId, templateId);
    }
}
