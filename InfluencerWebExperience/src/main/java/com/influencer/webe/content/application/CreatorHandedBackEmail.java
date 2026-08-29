package com.influencer.webe.content.application;

import com.influencer.webe.shared.application.EmailPort;

/**
 * Tells a brand that a creator has finished and sent the page back (roadmap PR-44).
 *
 * <p>The return leg of the handoff, and the one message in this flow with somebody waiting on the
 * other end of it. The creator has stopped work; until the brand looks, nothing moves.
 *
 * <p><b>The creator's note is included verbatim when they left one.</b> It is the closest thing to
 * a handover conversation the product has, and summarising or truncating it would lose exactly the
 * "I changed the headline because…" that makes the review quick.
 *
 * <p>Text-only: the page name, the creator's name and their note are all user-supplied, and none of
 * them gain anything from HTML.
 */
public final class CreatorHandedBackEmail {

    private CreatorHandedBackEmail() {
    }

    /**
     * @param to          the user who granted the access — they asked for this work
     * @param creatorName the creator's display name, or null if they never set one
     * @param pageName    the page's name, or null
     * @param note        what the creator said on returning it, or null
     * @param manageUrl   deep link to the page, or null when no UI base is configured
     */
    public static EmailPort.Message compose(String to, String creatorName, String pageName,
                                            String note, String manageUrl) {
        String creator = blankTo(creatorName, "A creator");
        String page = blankTo(pageName, "your campaign page");

        StringBuilder body = new StringBuilder();
        body.append(creator).append(" has finished their changes to ").append(page)
                .append(" and sent it back to you.\n\n");

        if (note != null && !note.isBlank()) {
            // Verbatim, and set apart so it reads as theirs rather than as ours.
            body.append("They said:\n\n").append(note.trim()).append("\n\n");
        }

        if (manageUrl != null && !manageUrl.isBlank()) {
            body.append("Review it here:\n").append(manageUrl.trim()).append("\n\n");
        }

        // Says who publishes, because that is the question the brand is about to have. The creator
        // cannot: content:publish is an account permission, and the collaborator rights constraint
        // has no publish value.
        body.append("Nothing is live until you publish it.\n");

        return EmailPort.Message.text(to,
                creator + " sent back " + page, body.toString());
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
