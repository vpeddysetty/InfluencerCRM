package com.influencer.webe.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules governing a deletion request, with no I/O.
 *
 * <p>Pure and static for the same reason {@link EmailVerificationPolicy} is: these decide whether an
 * account is destroyed, and that judgement should be testable without a database, a mailbox or a
 * clock. {@code DeletionRequestService} does the I/O these rules govern.
 *
 * <h2>The rule everything else serves</h2>
 *
 * <p><b>An inbound email is a claim, not an authorisation.</b> Email sender addresses are trivially
 * forged, so treating "delete me" as sufficient would let anyone destroy anyone else's account by
 * sending a message that claims to be from them. Intake records and notifies; a human approves.
 * V40 puts the same rule in a CHECK constraint so a bug here still cannot complete an unapproved
 * request.
 */
public final class DeletionRequestPolicy {

    private DeletionRequestPolicy() {
    }

    /**
     * How long an approval link stays usable.
     *
     * <p>Bounded because the link is a standing authorisation to destroy an account: one that never
     * expires sits in a mailbox indefinitely, and a compromise a year later could still use it.
     * Seven days so a request arriving on a Friday is still actionable after a week away.
     */
    public static final Duration APPROVAL_TTL = Duration.ofDays(7);

    /**
     * What /data-deletion/ promises: acknowledge in five business days, complete within thirty.
     *
     * <p>Calendar days, deliberately. "Five business days" needs a holiday calendar to compute and
     * the published promise is the outer bound either way; a report that sorts on a slightly early
     * date errs toward acting sooner, which is the safe direction for a rights request.
     */
    public static final Duration ACKNOWLEDGE_WITHIN = Duration.ofDays(5);
    public static final Duration COMPLETE_WITHIN = Duration.ofDays(30);

    public static final String SCOPE_ACCOUNT = "account";
    public static final String SCOPE_PROVIDER = "provider";

    public static final String SOURCE_EMAIL = "email";
    public static final String SOURCE_MANUAL = "manual";

    /**
     * Why a request was refused when the requester owns a workspace.
     *
     * <p>Deleting a brand owner destroys creator records the BRAND is controller for -- other
     * people's personal data, held under the brand's legal basis, not the owner's. One person's
     * erasure request is not authority to erase a third party's records, and teammates would lose
     * access with no notice. So the request is refused with an explanation and a route: transfer
     * ownership, or say explicitly that the workspace and its records are meant to go too.
     *
     * <p>V37 anticipated exactly this by giving the table {@code refused_at} and
     * {@code refused_reason} and calling a refusal an outcome rather than an error.
     */
    public static final String REFUSED_OWNS_WORKSPACE =
            "The account that sent this request owns one or more workspaces containing records "
            + "about other people. Deleting it would erase data the workspace is responsible for, "
            + "not only the requester's own. Transfer ownership first, or confirm in writing that "
            + "the workspace and everything in it should also be deleted.";

    public static final String REFUSED_UNKNOWN_ADDRESS =
            "No account matches the address this request came from, so there is nothing to attribute "
            + "it to. /data-deletion/ says we must refuse what we cannot attribute.";

    /**
     * Whether a message body reads as a deletion request.
     *
     * <p>Deliberately generous. A false positive costs an operator one glance at a notification they
     * were going to receive anyway; a false negative drops a rights request on the floor and the
     * requester never learns it was ignored. The asymmetry is the whole design.
     *
     * <p>This is a triage aid, not a gate: everything that arrives is recorded and notified
     * regardless of what this returns.
     */
    public static boolean readsAsDeletionRequest(String subject, String body) {
        String text = ((subject == null ? "" : subject) + " " + (body == null ? "" : body))
                .toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean mentionsRemoval = text.contains("delete") || text.contains("deletion")
                || text.contains("erase") || text.contains("erasure")
                || text.contains("remove") || text.contains("right to be forgotten")
                || text.contains("close my account") || text.contains("gdpr");
        boolean mentionsSubject = text.contains("account") || text.contains("data")
                || text.contains("my information") || text.contains("personal");
        return mentionsRemoval && mentionsSubject;
    }

    /**
     * Which provider a provider-scoped request names, or null when it is account-scoped.
     *
     * <p>Meta requires a route to delete only the data obtained from Facebook, leaving the account
     * intact, and their reviewers test it. /data-deletion/ section 3.2 promises it separately.
     */
    public static String providerNamedIn(String subject, String body) {
        String text = ((subject == null ? "" : subject) + " " + (body == null ? "" : body))
                .toLowerCase(Locale.ROOT);
        boolean scopedToProvider = text.contains("only") || text.contains("just")
                || text.contains("disconnect") || text.contains("unlink");
        if (!scopedToProvider) {
            return null;
        }
        if (text.contains("facebook") || text.contains("meta")) {
            return "facebook";
        }
        if (text.contains("google")) {
            return "google";
        }
        return null;
    }

    /** {@code account} unless the message clearly asks for one provider's data only. */
    public static String scopeFor(String subject, String body) {
        return providerNamedIn(subject, body) == null ? SCOPE_ACCOUNT : SCOPE_PROVIDER;
    }

    private static final Pattern ANGLE_ADDRESSED = Pattern.compile("<([^<>]+@[^<>]+)>");

    /**
     * The bare address from a {@code From} header.
     *
     * <p>{@code "Vijay Peddysetty" <a@b.com>} becomes {@code a@b.com}. Lowercased because the
     * lookup is against a citext column and a mixed-case header would otherwise miss an account
     * that exists -- which would present as "we cannot attribute your request" to someone whose
     * account is right there.
     */
    public static String addressFrom(String fromHeader) {
        if (fromHeader == null || fromHeader.isBlank()) {
            return null;
        }
        Matcher angle = ANGLE_ADDRESSED.matcher(fromHeader);
        String candidate = angle.find() ? angle.group(1) : fromHeader;
        candidate = candidate.trim().toLowerCase(Locale.ROOT);
        // A header can carry a display name with no angle brackets, or several addresses. Take the
        // first token that looks like an address rather than guessing at the rest.
        for (String part : candidate.split("[,;\\s]+")) {
            if (part.contains("@") && part.indexOf('@') > 0 && part.indexOf('@') < part.length() - 1) {
                return part;
            }
        }
        return null;
    }

    /** Whether an approval link is still usable. */
    public static boolean approvalUsable(Instant expiresAt, Instant approvedAt, Instant now) {
        if (approvedAt != null) {
            // Single use. A forwarded approval email must not stay live, and a second approval of
            // an already-executed purge would be a second irreversible act on the same authority.
            return false;
        }
        return expiresAt != null && expiresAt.isAfter(now);
    }

    /**
     * Whether the purge may run.
     *
     * <p>Every condition is a reason NOT to delete, and the default is refusal. Written this way
     * round on purpose: a bug that adds a condition fails closed, leaving data intact, which is the
     * recoverable direction.
     */
    public static boolean mayExecute(Instant approvedAt, Instant completedAt, Instant refusedAt) {
        if (approvedAt == null) {
            return false;
        }
        if (completedAt != null) {
            return false;
        }
        return refusedAt == null;
    }

    /** When acknowledgement is due, for the operator report. */
    public static Instant acknowledgementDue(Instant requestedAt) {
        return requestedAt == null ? null : requestedAt.plus(ACKNOWLEDGE_WITHIN);
    }

    /** When completion is due -- the promise on the published page. */
    public static Instant completionDue(Instant requestedAt) {
        return requestedAt == null ? null : requestedAt.plus(COMPLETE_WITHIN);
    }

    /** Whether a request has passed the published completion deadline and is still open. */
    public static boolean isOverdue(Instant requestedAt, Instant completedAt, Instant refusedAt,
                                    Instant now) {
        if (completedAt != null || refusedAt != null) {
            return false;
        }
        Instant due = completionDue(requestedAt);
        return due != null && now.isAfter(due);
    }
}
