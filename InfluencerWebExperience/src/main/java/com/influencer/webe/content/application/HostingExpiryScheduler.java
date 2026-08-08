package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.EmailPort;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Warns brands before free hosting runs out (roadmap M5.6).
 *
 * <p><b>The gap this closes.</b> Phase E built the hosting window (decision #11 — two months free
 * from first publish) and {@link BrandDomainService#extendHosting} to move it, but nothing ever
 * told a brand the clock was running. A published page simply stopped serving one day, with no
 * warning and no explanation. The roadmap's own note is that this is "live customer harm the
 * moment anyone publishes" — the endpoints were built and tested, and nothing called them.
 *
 * <p><b>Why the marker column rather than an exact day match.</b> The obvious implementation asks
 * "is this page exactly 7 days from expiry today?" and sends if so. That is wrong in a way that is
 * invisible until it matters: one missed run — a deploy, an outage, a container restart, a clock
 * crossing a DST boundary — and that threshold is skipped permanently, because tomorrow the answer
 * is 6. This asks instead "what is the smallest threshold now passed that has not been sent?",
 * which is both idempotent within a day and self-healing across missed days.
 *
 * <p><b>Disabled by default</b>, like {@code DomainEventRelay}. A job that starts emailing the
 * moment anyone runs the application — including from a test or a developer's machine pointed at a
 * shared database — is a bad surprise. Switched on deliberately via
 * {@code web-experience.hosting.expiry-warnings.enabled=true}.
 *
 * <p><b>Lives in content, reaches identity through HTTP only.</b> The owner's email comes from the
 * DAO's tenancy endpoints via the shared gateway client, not from {@code DaoTenancyClient}, which
 * is identity's own infrastructure and private to it — see {@code BffContextBoundaryTest}.
 */
@Component
@ConditionalOnProperty(name = "web-experience.hosting.expiry-warnings.enabled", havingValue = "true")
public class HostingExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HostingExpiryScheduler.class);

    /**
     * Days-remaining thresholds a brand is warned at, descending.
     *
     * <p>The sweep sends the <b>smallest</b> threshold a page has passed and not yet been warned
     * at, so a page that has passed several at once gets one mail — the most urgent. A page first
     * seen with hours left gets "1 day", not "30 days"; the latter would be actively wrong.
     */
    static final List<Integer> THRESHOLDS = List.of(30, 7, 1);

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final EmailPort email;
    private final String uiBaseUrl;

    public HostingExpiryScheduler(DaoGatewayClient dao, ResponseShapeService shape, EmailPort email,
                                  @Value("${web-experience.ui-base-url}") String uiBaseUrl) {
        this.dao = dao;
        this.shape = shape;
        this.email = email;
        this.uiBaseUrl = uiBaseUrl == null ? "" : uiBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Sweeps every page with a started hosting window and warns where one is due.
     *
     * <p>Daily by default. More often would send nothing extra — the marker makes a second run in
     * the same day a no-op — but would spend DAO calls for no gain.
     */
    @Scheduled(
            initialDelayString = "${web-experience.hosting.expiry-warnings.initial-delay-ms:60000}",
            fixedDelayString = "${web-experience.hosting.expiry-warnings.interval-ms:86400000}")
    public void warnExpiringPages() {
        int sent = 0;
        int failed = 0;

        try {
            JsonNode pages = dao.get("/landing-templates", Map.of());
            if (pages == null || !pages.isArray()) {
                return;
            }

            Instant now = Instant.now();
            for (JsonNode page : pages) {
                Integer threshold = thresholdDue(page, now);
                if (threshold == null) {
                    continue;
                }
                // Per-page try/catch: one page with a deleted brand or an unreachable mail
                // provider must not stop the rest of the sweep. A batch job that aborts on the
                // first bad row warns nobody after it, and the pages it skipped are exactly the
                // ones nobody notices until they go dark.
                try {
                    if (warn(page, threshold)) {
                        sent++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Could not warn about hosting expiry for page {}: {}",
                            page.path("id").asText("?"), e.toString());
                }
            }
        } catch (RuntimeException e) {
            // The DAO being briefly unreachable is not worth a stack trace at ERROR; the next run
            // picks up exactly the same pages, because nothing was marked.
            log.warn("Hosting expiry sweep could not run: {}", e.toString());
            return;
        }

        if (sent > 0 || failed > 0) {
            log.info("Hosting expiry sweep: {} warning(s) sent, {} failed", sent, failed);
        }
    }

    /**
     * The threshold a page is owed a warning at, or null if none is due.
     *
     * <p>Package-private so the decision — which is the part that can actually be wrong — is
     * testable without a scheduler, a DAO or a mail provider.
     */
    static Integer thresholdDue(JsonNode page, Instant now) {
        if (page == null || !page.hasNonNull("hostingExpiresAt")) {
            // NULL expiry means the clock never started. Not expiring, so nothing to warn about —
            // the same distinction BrandDomainService#isExpired makes.
            return null;
        }

        Instant expiresAt;
        try {
            expiresAt = Instant.parse(page.get("hostingExpiresAt").asText());
        } catch (Exception e) {
            // An unparseable date must not generate a warning, for the same reason it must not
            // take a live page down: we do not know what it means.
            return null;
        }

        if (!expiresAt.isAfter(now)) {
            // Already expired. The page is down and the brand has seen that; a "1 day left" mail
            // arriving after the fact is worse than silence.
            return null;
        }

        // Ceiling, not floor: with 6 hours left there is 1 day remaining, not 0. Flooring would
        // put the final day permanently below every threshold and skip the 1-day warning outright.
        long daysLeft = (long) Math.ceil(
                Duration.between(now, expiresAt).toMillis() / (double) Duration.ofDays(1).toMillis());

        Integer alreadySent = page.hasNonNull("hostingWarningSentAtDays")
                ? page.get("hostingWarningSentAtDays").asInt()
                : null;

        // The SMALLEST threshold the page has passed and not yet been warned at. Scanning
        // descending and returning the first passed one would answer 30 for a page with 20 hours
        // left; stopping as soon as a passed threshold has already been sent would answer "none"
        // for a page at day 7 that was warned at day 30, because 30 is passed and sent. Both are
        // wrong in the same direction — they under-warn near the deadline, which is precisely
        // where the warning matters.
        Integer due = null;
        for (Integer threshold : THRESHOLDS) {
            if (daysLeft > threshold) {
                continue;
            }
            // Thresholds descend, so a marker at or below this one means it is already covered.
            if (alreadySent != null && alreadySent <= threshold) {
                continue;
            }
            due = threshold;
        }
        return due;
    }

    // ---- internals -------------------------------------------------------

    /** @return true if a warning was actually sent. */
    private boolean warn(JsonNode page, int threshold) {
        String pageId = page.path("id").asText();
        String ownerEmail = ownerEmailFor(page.path("brandId").asText(null));
        if (ownerEmail == null) {
            // Nothing to send to. Not marked, so if the membership is fixed the warning still
            // goes out on the next run rather than being lost.
            log.warn("Page {} is {} day(s) from hosting expiry but its brand has no owner email",
                    pageId, threshold);
            return false;
        }

        EmailPort.Message message = HostingExpiryEmail.compose(
                ownerEmail,
                page.path("name").asText(null),
                domainNameFor(pageId),
                threshold,
                uiBaseUrl + "/landing/" + pageId);

        EmailPort.Result result = email.send(message);
        if (!result.sent()) {
            // Left unmarked on purpose: an unsent warning has not been given, and the next run
            // should try again rather than record it as delivered.
            log.warn("Hosting expiry warning for page {} was not sent ({}): {}",
                    pageId, result.provider(), result.detail());
            return false;
        }

        markWarned(page, threshold);
        return true;
    }

    /**
     * Records the threshold so the next run does not repeat it.
     *
     * <p>Written after a successful send, never before. The failure modes are not symmetric: mark
     * first and a provider outage silently consumes the warning, so the brand is never told;
     * send first and a crash between the two at worst repeats one email.
     */
    private void markWarned(JsonNode page, int threshold) {
        ObjectNode body = page.deepCopy();
        body.put("hostingWarningSentAtDays", threshold);
        // jsonb columns map as String on the entity; sending an object fails the PUT. Same guard
        // BrandDomainService applies for the same reason.
        for (String field : List.of("document", "blocks", "theme")) {
            JsonNode value = body.get(field);
            if (value != null && (value.isObject() || value.isArray())) {
                try {
                    body.put(field, shape.objectMapper().writeValueAsString(value));
                } catch (Exception ignored) {
                    body.remove(field);
                }
            }
        }
        dao.put("/landing-templates/" + page.path("id").asText(), body);
    }

    /** The account owner's address, or null if the brand, account or owner cannot be resolved. */
    private String ownerEmailFor(String brandId) {
        if (brandId == null || brandId.isBlank()) {
            return null;
        }
        JsonNode brand = dao.get("/tenancy/brands/" + brandId, null);
        if (brand == null || !brand.hasNonNull("accountId")) {
            return null;
        }
        JsonNode members = dao.get("/tenancy/accounts/" + brand.get("accountId").asText() + "/members",
                Map.of());
        if (members == null || !members.isArray()) {
            return null;
        }

        // The owner is who this concerns: hosting is a billing matter, and OWNER is the role that
        // carries billing. Falling back to any member would mail a viewer about a payment
        // decision they cannot make.
        String fallback = null;
        for (JsonNode member : members) {
            String address = member.path("email").asText(null);
            if (address == null || address.isBlank()) {
                continue;
            }
            if ("OWNER".equalsIgnoreCase(member.path("role").asText(""))) {
                return address;
            }
            if (fallback == null && "ADMIN".equalsIgnoreCase(member.path("role").asText(""))) {
                fallback = address;
            }
        }
        // An admin is second best — an account whose owner row is missing still has someone who
        // can act, and silence would be worse than mailing them.
        return fallback;
    }

    /** The custom domain serving a page, if one is connected — used to make the mail concrete. */
    private String domainNameFor(String pageId) {
        try {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("landingTemplateId", pageId);
            JsonNode rows = dao.get("/brand-domains", q);
            if (rows != null && rows.isArray() && rows.size() > 0) {
                return rows.get(0).path("domainName").asText(null);
            }
        } catch (RuntimeException e) {
            // Cosmetic. The warning is worth sending without naming the domain.
            log.debug("Could not resolve domain for page {}: {}", pageId, e.toString());
        }
        return null;
    }
}
