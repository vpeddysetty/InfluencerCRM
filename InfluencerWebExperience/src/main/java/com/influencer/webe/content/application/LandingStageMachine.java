package com.influencer.webe.content.application;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The allowed-transition map for a landing page (roadmap §4 rule 2).
 *
 * <p><b>Why a map rather than free movement.</b> "Draft → Published skipping review" is a
 * product decision, not a drag gesture. Holding the rule here means the same answer is given
 * whether the change came from the board, the builder, or the API — which is the entire point
 * of rule 1 (content owns the transition). A board that could write any stage directly would
 * make this unenforceable.
 *
 * <p><b>Backwards moves are allowed; skipping forwards is not.</b> Work genuinely goes
 * backwards — a page in review gets sent back for edits, a published page is pulled. Blocking
 * that would have people delete and recreate pages to get around it, losing the history. What
 * is blocked is jumping *ahead* of a gate: reaching Published without having been approved.
 */
@Component
public class LandingStageMachine {

    public static final String DRAFT = "draft";
    public static final String REVIEW = "review";
    public static final String APPROVED = "approved";
    public static final String CREATOR_ASSIGNED = "creator_assigned";
    public static final String CONTENT_NEEDED = "content_needed";
    public static final String READY_TO_PUBLISH = "ready_to_publish";
    public static final String PUBLISHED = "published";
    public static final String PERFORMANCE_TRACKING = "performance_tracking";

    /** The eight stages, in their natural order. */
    public static final List<String> STAGES = List.of(
            DRAFT, REVIEW, APPROVED, CREATOR_ASSIGNED,
            CONTENT_NEEDED, READY_TO_PUBLISH, PUBLISHED, PERFORMANCE_TRACKING);

    private static final Map<String, Set<String>> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put(DRAFT, Set.of(REVIEW, APPROVED));
        // Review can approve or bounce back. Both are ordinary.
        ALLOWED.put(REVIEW, Set.of(DRAFT, APPROVED));
        ALLOWED.put(APPROVED, Set.of(REVIEW, CREATOR_ASSIGNED, CONTENT_NEEDED, READY_TO_PUBLISH));
        ALLOWED.put(CREATOR_ASSIGNED, Set.of(APPROVED, CONTENT_NEEDED, READY_TO_PUBLISH));
        ALLOWED.put(CONTENT_NEEDED, Set.of(CREATOR_ASSIGNED, READY_TO_PUBLISH));
        ALLOWED.put(READY_TO_PUBLISH, Set.of(CONTENT_NEEDED, APPROVED, PUBLISHED));
        // A published page can be pulled back to ready_to_publish (unpublish) or move on to
        // tracking. It cannot jump back to draft: that would strand a live URL.
        ALLOWED.put(PUBLISHED, Set.of(READY_TO_PUBLISH, PERFORMANCE_TRACKING));
        ALLOWED.put(PERFORMANCE_TRACKING, Set.of(PUBLISHED));
    }

    public boolean isStage(String stage) {
        return stage != null && STAGES.contains(normalize(stage));
    }

    /** A no-op transition is allowed: re-sending the same stage must not be an error. */
    public boolean isAllowed(String from, String to) {
        String f = normalize(from);
        String t = normalize(to);
        if (!isStage(t)) {
            return false;
        }
        if (f.equals(t)) {
            return true;
        }
        return ALLOWED.getOrDefault(f, Set.of()).contains(t);
    }

    /** Stages reachable from here — used to tell a user WHY a drag was refused. */
    public Set<String> allowedFrom(String from) {
        return new LinkedHashSet<>(ALLOWED.getOrDefault(normalize(from), Set.of()));
    }

    /**
     * True when reaching this stage does more than change a label (§4 rule 3).
     *
     * <p>Moving to Published triggers a deploy and must fail if the page has never been
     * rendered. Refusing at the command boundary is exactly why rule 1 matters: a card that
     * had already moved would need compensating, and compensating a UI drag is far worse than
     * refusing it in the first place.
     */
    public boolean requiresPublishablePage(String to) {
        return PUBLISHED.equals(normalize(to));
    }

    private String normalize(String stage) {
        return stage == null ? "" : stage.trim().toLowerCase(Locale.ROOT);
    }
}
