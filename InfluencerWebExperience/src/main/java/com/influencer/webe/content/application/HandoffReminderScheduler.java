package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Nudges a handoff that has stopped moving (roadmap PR-44).
 *
 * <p><b>Ghosting is the modal outcome in creator marketing, not an edge case.</b> Every candidate
 * design for this feature assumed forward motion — brand hands off, creator edits, creator hands
 * back — and the common real ending is that nothing happens at all. Without this the page sits with
 * {@code turn = 'creator'} indefinitely, and the brand finds out the week of the campaign.
 *
 * <p><b>Two nudges, to two people, for two reasons.</b> Day three the creator is reminded, because
 * the usual cause is a forgotten email. Day seven the BRAND is told, because by then the likely
 * cause is that the creator is not going to do it, and the useful action — chase them, or take the
 * page back — belongs to the brand. Reminding the creator forever would be nagging somebody who
 * has already decided.
 *
 * <p><b>Idempotency comes from the stamp, not from the schedule.</b> Without
 * {@code handoff_reminder_sent_at} an hourly sweep would see "three days elapsed" at hour 72, 73
 * and 74 and email every hour until the creator acted — worse than no reminder, and how a sending
 * domain gets marked as spam. A reminder counts only if it was sent AFTER the turn last moved, so
 * passing the page back and forth re-arms the sweep with nothing to reset.
 *
 * <p><b>OP-17 applies.</b> This is plain {@code @Scheduled} with no ShedLock, so a second instance
 * would send each nudge twice — both having read the row before either wrote the stamp. One
 * instance serves production today and the scheduled-publish sweep already lives with the same
 * constraint; recorded here rather than left to be rediscovered.
 */
@Component
public class HandoffReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(HandoffReminderScheduler.class);

    /** Long enough that a creator who reads mail twice a week is not chased for nothing. */
    private static final Duration REMIND_CREATOR_AFTER = Duration.ofDays(3);

    /** By now the brand needs to know, whether or not the creator ever replies. */
    private static final Duration TELL_BRAND_AFTER = Duration.ofDays(7);

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final CollaboratorNotifier notifier;
    private final boolean enabled;

    public HandoffReminderScheduler(DaoGatewayClient dao,
                                    ResponseShapeService shape,
                                    CollaboratorNotifier notifier,
                                    @Value("${web-experience.handoff-reminders-enabled:true}") boolean enabled) {
        this.dao = dao;
        this.shape = shape;
        this.notifier = notifier;
        this.enabled = enabled;
    }

    /**
     * Hourly, not by the minute.
     *
     * <p>The thresholds are measured in days, so a sweep running every minute would do the same
     * work 1,440 times to change an answer once. Hourly also means a missed run costs an hour of
     * lateness on a three-day reminder, which nobody notices.
     */
    @Scheduled(fixedDelayString = "${web-experience.handoff-reminders-interval-ms:3600000}",
            initialDelayString = "${web-experience.handoff-reminders-initial-delay-ms:120000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            // Only pages that have been waiting at least as long as the SHORTER threshold. The
            // partial index on (turn, turn_changed_at) from V45 exists for exactly this — most
            // pages have a null turn and are never examined.
            Instant cutoff = Instant.now().minus(REMIND_CREATOR_AFTER);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("before", cutoff.toString());
            JsonNode pages = dao.get("/landing-templates/awaiting-turn", query);
            if (pages == null || !pages.isArray()) {
                return;
            }
            Instant now = Instant.now();
            for (JsonNode page : pages) {
                try {
                    consider(page, now);
                } catch (RuntimeException e) {
                    // One malformed row must not strand the rest of the sweep — the same rule the
                    // scheduled-publish sweep follows, and for the same reason.
                    log.warn("Handoff reminder skipped for page {}: {}",
                            page.path("id").asText("?"), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Handoff reminder sweep failed: {}", e.toString());
        }
    }

    private void consider(JsonNode page, Instant now) {
        if (!HandoffMachine.CREATOR.equals(page.path("turn").asText(null))
                || !page.hasNonNull("turnChangedAt")) {
            // Only a page sitting with the CREATOR is abandoned in the sense this sweep means. One
            // waiting on the brand is their own backlog, and emailing somebody about their own
            // to-do list is noise.
            return;
        }

        Instant since = Instant.parse(page.get("turnChangedAt").asText());
        if (alreadyRemindedSinceTurnMoved(page, since)) {
            return;
        }

        Duration waited = Duration.between(since, now);
        UUID templateId = UUID.fromString(page.get("id").asText());
        UUID brandId = UUID.fromString(page.get("brandId").asText());

        // Longest threshold FIRST, so a page that has waited eight days escalates to the brand
        // rather than sending the creator a reminder it has already outgrown.
        boolean sent;
        if (waited.compareTo(TELL_BRAND_AFTER) >= 0) {
            sent = notifier.notifyStalledToBrand(brandId, templateId, waited.toDays());
        } else {
            sent = notifier.remindCreator(brandId, templateId);
        }

        // Stamped only when something was actually sent. A reminder that could not be delivered
        // must not be recorded, or a transient mail failure silences this page permanently —
        // exactly the outcome the sweep exists to prevent.
        if (sent) {
            stampReminded(page, templateId, now);
        }
    }

    /**
     * Has a reminder gone out since the turn last moved?
     *
     * <p>Comparing the two timestamps rather than clearing the stamp on handoff is what makes
     * passing a page back and forth re-arm the sweep with nothing to reset.
     */
    private boolean alreadyRemindedSinceTurnMoved(JsonNode page, Instant turnChangedAt) {
        if (!page.hasNonNull("handoffReminderSentAt")) {
            return false;
        }
        return Instant.parse(page.get("handoffReminderSentAt").asText()).isAfter(turnChangedAt);
    }

    private void stampReminded(JsonNode page, UUID templateId, Instant now) {
        ObjectNode body = shape.objectMapper().createObjectNode();
        // The DAO's PUT replaces the row, so the identity fields have to be restated — and the
        // turn with them, since it is null-guarded there and this write must not drop the page out
        // of the very list the sweep just read it from.
        body.put("brandId", page.get("brandId").asText());
        body.put("campaignId", page.get("campaignId").asText());
        body.put("publicSlug", page.get("publicSlug").asText());
        body.put("name", page.path("name").asText("Landing page"));
        body.put("status", page.path("status").asText("draft"));
        body.put("stage", page.path("stage").asText(LandingStageMachine.DRAFT));
        body.put("turn", page.path("turn").asText());
        body.put("turnChangedAt", page.get("turnChangedAt").asText());
        body.put("handoffReminderSentAt", now.toString());
        LandingTemplateWrites.carryForwardScheduledPublish(page, body);
        dao.put("/landing-templates/" + templateId, body);
    }
}
