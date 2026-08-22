package com.influencer.webe.identity.application;

import com.influencer.webe.shared.application.EmailPort;

/**
 * Composes the address-verification email.
 *
 * <p>Separate from the service that sends it for the same reason {@link InvitationEmail} is: what a
 * message says is a product decision, and who may trigger one is an authorization decision.
 *
 * <p><b>Text-only, deliberately</b> — the body carries a one-time token in a URL, and an HTML body
 * would add an injection surface for no gain a bare link does not already provide.
 *
 * <p><b>It must read as expected mail, not as a security alarm.</b> This arrives seconds after
 * someone deliberately signed up, so the tone is a receipt with a link rather than a warning. The
 * one warning that does belong — what to do if you did NOT sign up — goes at the end, because a
 * recipient who did sign up should not have to read past an alarm to find the link.
 */
public final class VerificationEmail {

    private VerificationEmail() {
    }

    /**
     * Builds the verification message.
     *
     * @param to         the address being proven — the token was issued for this address, not for
     *                   whatever the account's email column says now
     * @param verifyUrl  the fully-formed verification link, token included
     * @param ttlHours   how long the link lives, stated so the recipient can judge urgency
     */
    public static EmailPort.Message compose(String to, String verifyUrl, long ttlHours) {
        String subject = "Confirm your email address for Tejdux";

        // The link sits on its own line, unadorned. Mail clients linkify a bare URL reliably;
        // wrapping it in punctuation is what breaks the click target and produces a token that
        // silently fails to redeem.
        String body = """
                Confirm this address to finish setting up your Tejdux account:

                %s

                The link works once and expires in %d hours. If it expires, sign in and we will
                send you a new one.

                Why we ask: this address is where password resets and account notices go, so we
                confirm you can receive mail here before the account is usable.

                If you did not sign up for Tejdux, you can ignore this email. The account cannot
                be used until this link is clicked, and it will be removed on its own.
                """.formatted(verifyUrl, ttlHours);

        return EmailPort.Message.text(to, subject, body);
    }
}
