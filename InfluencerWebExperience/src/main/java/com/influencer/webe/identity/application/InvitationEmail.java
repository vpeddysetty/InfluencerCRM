package com.influencer.webe.identity.application;

import com.influencer.webe.shared.application.EmailPort;

/**
 * Composes the member-invitation email (roadmap M1.4).
 *
 * <p>Separate from {@link MemberInvitationService} because composing a message and deciding who
 * may be invited are different jobs, and separate from {@link EmailPort} because what an
 * invitation says is a product decision, not a delivery concern.
 *
 * <p><b>Text-only, deliberately.</b> The body carries a one-time token in a URL. An HTML body adds
 * an injection surface (brand names and inviter names are user-supplied) for no gain a plain link
 * does not already provide. If HTML is added later, those two fields are what need escaping.
 */
public final class InvitationEmail {

    private InvitationEmail() {
    }

    /**
     * Builds the invitation message.
     *
     * @param to           the invited email address
     * @param brandName    the workspace they are being invited to
     * @param inviterName  who invited them — recipients ignore invitations from nobody
     * @param role         the role they will hold
     * @param acceptUrl    the fully-formed accept link, token included
     */
    public static EmailPort.Message compose(String to, String brandName, String inviterName,
                                            String role, String acceptUrl) {
        String workspace = blankTo(brandName, "a workspace");
        String inviter = blankTo(inviterName, "A teammate");

        String subject = inviter + " invited you to " + workspace + " on InfluenCRM";

        // The link is on its own line and unadorned. Mail clients linkify a bare URL reliably;
        // wrapping it in punctuation is what breaks the click target and produces a token that
        // silently fails to redeem.
        String body = """
                %s invited you to join %s on InfluenCRM as %s.

                Accept the invitation:

                %s

                This link works once and expires in 7 days. If it has expired, ask %s to send
                a new one — the original cannot be recovered.

                If you were not expecting this invitation, you can ignore this email. No account
                is created and no access is granted until the link is used.
                """.formatted(inviter, workspace, article(role) + " " + role, acceptUrl, inviter);

        return EmailPort.Message.text(to, subject, body);
    }

    /** "an admin" reads better than "a admin"; roles are a closed set so this stays simple. */
    private static String article(String role) {
        if (role == null || role.isBlank()) {
            return "a";
        }
        return "aeiouAEIOU".indexOf(role.charAt(0)) >= 0 ? "an" : "a";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
