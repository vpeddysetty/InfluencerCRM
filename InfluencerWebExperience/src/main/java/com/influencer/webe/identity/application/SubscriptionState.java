package com.influencer.webe.identity.application;

import java.util.Locale;
import java.util.Set;

/**
 * The subscription lifecycle, as rules rather than scattered {@code if} statements.
 *
 * <p>Pure and static so the transitions can be tested without a database, a payment provider, or a
 * web request — the same reason {@code LandingStageMachine} exists for page stages. Every way this
 * can go wrong is silent: a state that can be reached but not left strands a paying customer, and
 * a transition that skips a guard can grant paid limits to an account that is not paying.
 *
 * <h2>The states</h2>
 * <ul>
 *   <li>{@code trialing} — created, not yet charged.</li>
 *   <li>{@code active} — the normal paying state.</li>
 *   <li>{@code paused} — billing suspended, resumable, data intact.</li>
 *   <li>{@code past_due} — a charge failed. Still active-ish: the provider is retrying, and cutting
 *       service off on the first failed card is how you lose a customer to an expired card.</li>
 *   <li>{@code cancelled} — terminal. A new subscription is a new row, which is why the unique
 *       index that allows one live subscription per account excludes this state.</li>
 * </ul>
 */
public final class SubscriptionState {

    public static final String TRIALING = "trialing";
    public static final String ACTIVE = "active";
    public static final String PAUSED = "paused";
    public static final String PAST_DUE = "past_due";
    public static final String CANCELLED = "cancelled";

    /** Must match the CHECK constraint on {@code identity.subscriptions.status}. */
    public static final Set<String> ALL = Set.of(TRIALING, ACTIVE, PAUSED, PAST_DUE, CANCELLED);

    /**
     * States in which the account is entitled to its paid plan.
     *
     * <p>{@code past_due} is included deliberately. A failed charge is usually an expired card, and
     * the provider retries for days; downgrading someone's limits the moment a renewal fails would
     * break their workspace over a payment problem they may not know about yet and are about to
     * fix. {@code paused} is excluded just as deliberately — that is the whole point of pausing.
     */
    private static final Set<String> ENTITLED = Set.of(TRIALING, ACTIVE, PAST_DUE);

    private SubscriptionState() {
    }

    public static boolean isKnown(String status) {
        return ALL.contains(normalize(status));
    }

    /** Whether this status should grant the subscription's paid plan. */
    public static boolean grantsPaidPlan(String status) {
        return ENTITLED.contains(normalize(status));
    }

    /** Terminal: nothing may leave it, and a new subscription means a new row. */
    public static boolean isTerminal(String status) {
        return CANCELLED.equals(normalize(status));
    }

    public static boolean canPause(String status) {
        String current = normalize(status);
        // Pausing a past_due subscription is refused on purpose: it would look like a way to stop
        // a failed charge from retrying, and it is not — the debt is still owed. Fix the card, or
        // cancel.
        return ACTIVE.equals(current) || TRIALING.equals(current);
    }

    public static boolean canResume(String status) {
        return PAUSED.equals(normalize(status));
    }

    /** Anything not already finished can be cancelled — including a paused subscription. */
    public static boolean canCancel(String status) {
        return isKnown(status) && !isTerminal(status);
    }

    /**
     * Whether a move is allowed.
     *
     * <p>Same-state moves are permitted so a replayed webhook is a no-op rather than an error —
     * providers deliver at-least-once, so the second {@code subscription.updated} carrying the same
     * status is expected traffic, not a fault.
     */
    public static boolean canTransition(String from, String to) {
        String current = normalize(from);
        String next = normalize(to);
        if (!isKnown(current) || !isKnown(next)) {
            return false;
        }
        if (current.equals(next)) {
            return true;
        }
        if (isTerminal(current)) {
            return false;
        }
        return switch (next) {
            case CANCELLED -> true;
            case PAUSED -> canPause(current);
            // Resuming from paused, recovering from a failed charge, or converting a trial.
            case ACTIVE -> PAUSED.equals(current) || PAST_DUE.equals(current) || TRIALING.equals(current);
            case PAST_DUE -> ACTIVE.equals(current) || TRIALING.equals(current);
            // Nothing goes back to trialing. A trial is offered once; re-entering it would be a
            // free extension available to anyone who could trigger the right webhook.
            case TRIALING -> false;
            default -> false;
        };
    }

    /**
     * The plan to enforce, given a subscription's billed plan and status.
     *
     * <p>This is the bridge between billing and {@code PlanPolicy}: {@code accounts.plan} is what
     * gets enforced, and it is derived from here. A paused or cancelled subscription drops to
     * {@code free} while the row keeps its paid plan, which is exactly why the two columns exist
     * separately.
     */
    public static String effectivePlan(String billedPlan, String status) {
        if (!grantsPaidPlan(status)) {
            return PlanPolicy.FREE.key();
        }
        // Fails closed through PlanPolicy: an unrecognised plan string resolves to free rather
        // than to unlimited.
        return PlanPolicy.forKey(billedPlan).key();
    }

    /** Human-readable status, for a UI that should not render a raw enum. */
    public static String label(String status) {
        return switch (normalize(status)) {
            case TRIALING -> "Trial";
            case ACTIVE -> "Active";
            case PAUSED -> "Paused";
            case PAST_DUE -> "Payment failed";
            case CANCELLED -> "Cancelled";
            default -> "Unknown";
        };
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }
}
