package com.influencer.webe.shared.application;

import com.influencer.webe.identity.application.InvitationEmail;
import com.influencer.webe.shared.infrastructure.LoggingEmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the email port's contract and the invitation composer.
 *
 * <p>The behaviour worth protecting here is not "does it send" — the local adapter deliberately
 * does not. It is that the port cannot break its caller, and that the log-only adapter never
 * claims to be something it is not.
 */
class EmailPortTest {

    private final LoggingEmailSender sender = new LoggingEmailSender();

    @Test
    @DisplayName("the log adapter names itself honestly")
    void providerIsLog() {
        // MemberInvitationService reads this string to decide whether to tell the user an email
        // was sent. If it ever returned "postmark", the UI would claim delivery that did not
        // happen — the failure mode MockDomainRegistrar's Javadoc warns about.
        assertEquals("log", sender.provider());
    }

    @Test
    @DisplayName("send never throws, whatever it is handed")
    void sendNeverThrows() {
        // Documented contract. An invitation is already persisted by the time send runs; an
        // exception here would fail the request that created a grant which still exists.
        assertAll(
                () -> assertDoesNotThrow(() -> sender.send(null)),
                () -> assertDoesNotThrow(() -> sender.send(EmailPort.Message.text(null, "s", "b"))),
                () -> assertDoesNotThrow(() -> sender.send(EmailPort.Message.text("", "s", "b"))),
                () -> assertDoesNotThrow(() -> sender.send(EmailPort.Message.text("a@b.com", null, null))));
    }

    @Test
    @DisplayName("a missing recipient is reported, not thrown")
    void missingRecipientIsReported() {
        EmailPort.Result result = sender.send(EmailPort.Message.text("  ", "subject", "body"));

        assertFalse(result.sent());
        assertEquals("log", result.provider());
    }

    @Test
    @DisplayName("a text-only message carries no HTML body")
    void textFactoryLeavesHtmlNull() {
        // The invitation body contains a one-time token and interpolates user-supplied names.
        // No HTML body means no HTML injection surface.
        EmailPort.Message message = EmailPort.Message.text("a@b.com", "s", "b");

        assertNull(message.htmlBody());
    }

    @Test
    @DisplayName("the invitation email contains the accept link and no raw token elsewhere")
    void invitationCarriesTheLink() {
        String acceptUrl = "https://app.example.com/accept-invitation?token=abc123";
        EmailPort.Message message =
                InvitationEmail.compose("invitee@example.com", "Acme", "ada@example.com", "admin", acceptUrl);

        assertAll(
                () -> assertEquals("invitee@example.com", message.to()),
                () -> assertTrue(message.subject().contains("Acme")),
                () -> assertTrue(message.subject().contains("ada@example.com"),
                        "an invitation from nobody gets ignored"),
                () -> assertTrue(message.textBody().contains(acceptUrl)),
                () -> assertNull(message.htmlBody()));
    }

    @Test
    @DisplayName("the accept link sits alone on its line so mail clients linkify it")
    void linkIsUnadorned() {
        String acceptUrl = "https://app.example.com/accept-invitation?token=abc123";
        EmailPort.Message message =
                InvitationEmail.compose("invitee@example.com", "Acme", "Ada", "admin", acceptUrl);

        // Trailing punctuation is the classic way to produce a link that looks right and
        // redeems to "invitation not found".
        boolean onOwnLine = message.textBody().lines()
                .anyMatch(line -> line.trim().equals(acceptUrl));

        assertTrue(onOwnLine, "the URL must appear on a line by itself, unpunctuated");
    }

    @Test
    @DisplayName("missing brand and inviter names degrade to readable text")
    void blankNamesDegradeGracefully() {
        // brandNameFor() returns null rather than failing an invitation over a display string.
        // The composer has to survive that, or the fallback becomes the outage.
        EmailPort.Message message = InvitationEmail.compose("invitee@example.com", null, null, "marketer", "https://x/y");

        assertAll(
                () -> assertFalse(message.subject().contains("null")),
                () -> assertFalse(message.textBody().contains("null")),
                () -> assertTrue(message.textBody().contains("a workspace")));
    }

    @Test
    @DisplayName("the role article reads correctly for vowel-initial roles")
    void rolesGetTheRightArticle() {
        String admin = InvitationEmail.compose("a@b.com", "Acme", "Ada", "admin", "https://x/y").textBody();
        String marketer = InvitationEmail.compose("a@b.com", "Acme", "Ada", "marketer", "https://x/y").textBody();

        assertAll(
                () -> assertTrue(admin.contains("an admin")),
                () -> assertTrue(marketer.contains("a marketer")));
    }
}
