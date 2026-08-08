package com.influencer.webe.content.application;

import com.influencer.webe.shared.application.EmailPort;

/**
 * Composes the free-hosting expiry warning (roadmap M5.6).
 *
 * <p>Separate from {@link HostingExpiryScheduler} for the same reason {@link
 * com.influencer.webe.identity.application.InvitationEmail} is separate from the invitation
 * service: deciding <em>when</em> to warn and deciding <em>what the warning says</em> are different
 * jobs, and only the second is a product decision.
 *
 * <p><b>Text-only, deliberately.</b> The page name is user-supplied and would need escaping in an
 * HTML body; a plain link gives up nothing here.
 *
 * <p><b>The tone shifts with the deadline.</b> Thirty days out this is an FYI; one day out it is
 * the last chance to avoid a live page going dark. A single template for both either alarms people
 * a month early or under-warns them the day before.
 */
public final class HostingExpiryEmail {

    private HostingExpiryEmail() {
    }

    /**
     * Builds the warning.
     *
     * @param to        the account owner's email
     * @param pageName  the landing page whose hosting is expiring
     * @param domain    the domain it is served on, or null if it is on a platform subdomain
     * @param daysLeft  whole days remaining — 30, 7 or 1
     * @param manageUrl deep link to the page in the builder
     */
    public static EmailPort.Message compose(String to, String pageName, String domain,
                                            int daysLeft, String manageUrl) {
        String page = blankTo(pageName, "Your landing page");
        String where = domain == null || domain.isBlank() ? null : domain.trim();

        String subject = daysLeft <= 1
                ? "Your page goes offline tomorrow — " + page
                : "Free hosting for " + page + " ends in " + daysLeft + " days";

        // Say what happens, when, and what to do — in that order. A warning that leads with the
        // remedy reads as marketing and gets filed as such; one that never states the consequence
        // gets ignored until the page is already down.
        String consequence = daysLeft <= 1
                ? "Tomorrow it stops being served and visitors will see an error."
                : "When it ends, the page stops being served and visitors will see an error.";

        String served = where == null
                ? ""
                : "\nThe page is live at " + where + ". That domain stays yours either way — it is "
                        + "registered in your own account and we never held it.\n";

        String body = """
                Free hosting for "%s" ends in %s.

                %s
                %s
                Keep it online:

                %s

                Nothing is deleted when hosting ends. The page, its content and its analytics stay
                in your workspace, and it goes back online as soon as hosting is extended.
                """.formatted(page, days(daysLeft), consequence, served, manageUrl);

        return EmailPort.Message.text(to, subject, body);
    }

    /** "1 day" rather than "1 days" — the 1-day mail is the one people actually read. */
    private static String days(int daysLeft) {
        return daysLeft <= 1 ? "1 day" : daysLeft + " days";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
