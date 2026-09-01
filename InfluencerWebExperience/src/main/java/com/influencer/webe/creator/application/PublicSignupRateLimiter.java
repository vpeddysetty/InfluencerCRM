package com.influencer.webe.creator.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paces the public creator sign-up form, which is the only AI spend in the product reachable by
 * somebody who is not a customer (roadmap OP-25).
 *
 * <p><b>Why this exists.</b> {@code POST /api/public/landing/{slug}/signup} takes no
 * {@code Authorization} and is meant to — it backs the sign-up form on a published landing page,
 * so anyone who can see the page can post to it. It calls {@code captureLead}, which calls
 * {@code classify}, which is a billed OpenAI call. {@code AiGenerationAllowance} does not see it:
 * that caps an ACCOUNT's own users on the Anthropic path, and a stranger posting handles at a
 * public page is neither. Without a pace here, a loop against one published page bills the
 * platform for as long as it is left running.
 *
 * <p><b>Why not {@code LoginAttemptLimiter}.</b> It looked like the same problem and is not. That
 * one counts FAILURES and locks an address out, because on a login every failure is a guess and
 * success is the thing you want to allow without limit. Here the opposite holds: every submission
 * is a legitimate success that spends money, so counting failures would never trip. What is needed
 * is a ceiling on the RATE of successes — a different mechanism, kept separate rather than bent
 * out of the login one.
 *
 * <p><b>Keyed on the page, not the caller's address.</b> An IP key is trivially defeated by
 * spreading submissions, and worse, it punishes a shared network — an office or a campus behind
 * one NAT is exactly the audience a creator campaign wants. Keying on the slug bounds what any
 * single page can cost no matter who is posting, which is the quantity actually being protected.
 * The accepted cost is that a genuinely viral page hits its own ceiling; see below for why that
 * degrades rather than refuses.
 *
 * <p><b>In-memory, and honestly so</b> — the same caveat {@code LoginAttemptLimiter} carries.
 * One instance serves production today, so a map is the whole mechanism, and it resets on deploy.
 * A shared store before there is a second instance would be infrastructure ahead of need; when
 * there is one, this moves alongside it.
 */
@Component
public class PublicSignupRateLimiter {

    /**
     * How many enrichments one published page may buy per window.
     *
     * <p>Sized against the honest case, not the attack: a page doing well converts a few sign-ups
     * an hour, and thirty is far above that while still bounding a runaway loop to a trivial sum.
     * The point is a ceiling, not a gate.
     */
    private static final int MAX_ENRICHMENTS = 30;

    /** The window the ceiling applies over. Rolls forward from the first submission in it. */
    private static final Duration WINDOW = Duration.ofHours(1);

    private final boolean enabled;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public PublicSignupRateLimiter(
            @Value("${web-experience.creators.public-signup-rate-limit-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Whether this page may buy another AI enrichment right now.
     *
     * <p><b>A false is not a refusal of the sign-up.</b> The caller still captures the lead and
     * falls back to the free keyword classifier. That ordering is the whole design: the visitor
     * handed over their details and a brand wants that lead, so refusing it to save a fraction of
     * a cent would be the expensive mistake. What is dropped is the model's opinion of their
     * niche, which the heuristic already approximates and which is stamped {@code heuristic} so
     * nobody mistakes one for the other.
     */
    public boolean allowEnrichment(String slug) {
        if (!enabled) {
            return true;
        }
        Instant now = Instant.now();
        Window updated = windows.compute(normalize(slug), (ignored, existing) -> {
            if (existing == null || existing.startedAt.plus(WINDOW).isBefore(now)) {
                return new Window(1, now);
            }
            return new Window(existing.count + 1, existing.startedAt);
        });
        return updated.count <= MAX_ENRICHMENTS;
    }

    private String normalize(String slug) {
        return slug == null ? "" : slug.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record Window(int count, Instant startedAt) {
    }
}
