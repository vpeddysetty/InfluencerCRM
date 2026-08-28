package com.influencer.webe.content.application;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whose move it is, and who is allowed to change that (roadmap PR-40).
 *
 * <p><b>Turn is orthogonal to stage, and keeping them apart is the design.</b>
 * {@link LandingStageMachine} answers <i>how far along is this page</i>. This answers <i>whose
 * move is it</i>. They change for different reasons and at different rates: a page sits at
 * {@code content_needed} while the turn bounces brand → creator → brand three times over.
 * Collapsing them into one column is the obvious-looking simplification, and it breaks the first
 * time a creator hands work back that the brand then hands forward again.
 *
 * <p><b>{@code null} is a real state, not "unknown".</b> It means nobody owes anything — a solo
 * draft nobody has been invited to, or a published page where the work is finished. Defaulting it
 * to {@code brand} would put every page a brand has ever made into their "waiting on you" list.
 *
 * <p><b>What this class does NOT decide.</b> It says nothing about whether a stage change is
 * legal — that is the stage machine's job — and nothing about whether the caller holds a grant on
 * this page, which is {@code PageCollaborationService}'s. Three separate questions, deliberately
 * answered in three places: a single "can this person do this" method would have to know about
 * grants, permissions and product rules at once, and would be re-derived wrongly the first time
 * one of them changed.
 */
@Component
public class HandoffMachine {

    /** The brand side: owner, marketer, anyone holding the page through an operator session. */
    public static final String BRAND = "brand";

    /** The invited creator. */
    public static final String CREATOR = "creator";

    /** Nobody owes anything. Stored as SQL NULL — see the class note. */
    public static final String NOBODY = null;

    public static final List<String> TURNS = List.of(BRAND, CREATOR);

    /**
     * Stages at which a page can be handed to a creator.
     *
     * <p>Narrower than "any stage", because handing off a page that is already published, or one
     * still in first draft, is not a workflow anybody asked for — it is a mis-click. The set
     * matches the stages the collaboration design actually walks through.
     */
    private static final Set<String> HANDOFF_STAGES = Set.of(
            LandingStageMachine.APPROVED,
            LandingStageMachine.CREATOR_ASSIGNED,
            LandingStageMachine.CONTENT_NEEDED);

    public boolean isTurn(String turn) {
        return turn != null && TURNS.contains(normalize(turn));
    }

    /**
     * May a page at this stage be handed to a creator?
     *
     * <p>Checked before the grant is written rather than after, so a refused handoff leaves no
     * collaborator row behind — an orphaned grant would give a creator access to a page nobody
     * ever actually handed them.
     */
    public boolean canHandOff(String stage) {
        return stage != null && HANDOFF_STAGES.contains(normalize(stage));
    }

    /**
     * May the creator hand the page back right now?
     *
     * <p>Only when it is genuinely their turn. Without this check a creator could "return" a page
     * they were never given, or return one twice — the second click arriving after the brand had
     * already picked it up, silently taking it back off them.
     */
    public boolean canHandBack(String currentTurn) {
        return CREATOR.equals(normalize(currentTurn));
    }

    /**
     * May the brand take the page back from the creator?
     *
     * <p>Deliberately allowed even when it is the brand's turn already, and that is not sloppiness:
     * "take it back" is how a brand recovers from an accidental handoff or an unresponsive
     * creator, and refusing it because the turn column already says {@code brand} would block the
     * exact case where the two have drifted. Idempotent by intent.
     */
    public boolean canTakeBack(String currentTurn) {
        return true;
    }

    /**
     * The turn after a stage change, when the stage change itself implies one.
     *
     * <p>Only {@code published} does: once a page is live neither side owes the other anything, so
     * leaving the turn set would keep it in somebody's "waiting on you" list forever. Every other
     * stage leaves the turn alone, because a stage change is not a handoff — that is the whole
     * reason the two columns exist.
     *
     * @return the turn to write, or the {@code current} value when this stage implies nothing
     */
    public String turnAfterStage(String stage, String current) {
        String target = normalize(stage);
        if (LandingStageMachine.PUBLISHED.equals(target)
                || LandingStageMachine.PERFORMANCE_TRACKING.equals(target)) {
            return NOBODY;
        }
        return current;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
