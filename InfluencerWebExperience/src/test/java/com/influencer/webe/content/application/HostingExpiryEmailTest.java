package com.influencer.webe.content.application;

import com.influencer.webe.shared.application.EmailPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the expiry warning says — a product decision, so it is asserted rather than assumed. */
class HostingExpiryEmailTest {

    private static final String MANAGE = "https://app.example.com/landing/abc";

    @Test
    @DisplayName("the subject states the deadline, so it is readable in a notification")
    void subjectCarriesTheDeadline() {
        // Most people decide whether to open on the subject alone. One that says only "about your
        // hosting" gets triaged as marketing.
        assertTrue(HostingExpiryEmail.compose("o@e.com", "Summer", null, 30, MANAGE)
                .subject().contains("30 days"));
        assertTrue(HostingExpiryEmail.compose("o@e.com", "Summer", null, 1, MANAGE)
                .subject().toLowerCase().contains("tomorrow"));
    }

    @Test
    @DisplayName("the last warning is more urgent than the first")
    void toneEscalates() {
        String early = HostingExpiryEmail.compose("o@e.com", "Summer", null, 30, MANAGE).textBody();
        String late = HostingExpiryEmail.compose("o@e.com", "Summer", null, 1, MANAGE).textBody();

        assertTrue(early.contains("30 days"));
        // "1 day", never "1 days" — the day-before mail is the one that gets read closely.
        assertTrue(late.contains("1 day"));
        assertFalse(late.contains("1 days"));
        assertTrue(late.toLowerCase().contains("tomorrow"));
    }

    @Test
    @DisplayName("the body says what happens and how to stop it")
    void bodyStatesConsequenceAndRemedy() {
        String body = HostingExpiryEmail.compose("o@e.com", "Summer sale", null, 7, MANAGE).textBody();

        assertTrue(body.contains("Summer sale"));
        assertTrue(body.contains(MANAGE), "the mail must link to where the window is extended");
        assertTrue(body.toLowerCase().contains("stops being served"),
                "a warning that never states the consequence gets ignored");
        // Nothing is deleted at expiry — saying so prevents a panic migration off the platform,
        // which is the reaction to a warning that sounds like data loss.
        assertTrue(body.toLowerCase().contains("nothing is deleted"));
    }

    @Test
    @DisplayName("a connected domain is named, and reassures rather than alarms")
    void namesTheDomainWhenThereIsOne() {
        String body = HostingExpiryEmail
                .compose("o@e.com", "Summer", "shop.acme.com", 7, MANAGE).textBody();

        assertTrue(body.contains("shop.acme.com"));
        // Decision #9 — the brand bought the domain themselves. A hosting warning that reads as
        // "you may lose your domain" would be false and frightening.
        assertTrue(body.toLowerCase().contains("stays yours"));
    }

    @Test
    @DisplayName("with no domain the mail simply omits it")
    void omitsDomainWhenAbsent() {
        // A page on a platform subdomain has no custom domain; the sentence about it must not
        // appear as an empty gap or a dangling "live at ".
        String body = HostingExpiryEmail.compose("o@e.com", "Summer", null, 7, MANAGE).textBody();

        assertFalse(body.contains("live at"));
        assertFalse(body.contains("  "), "no gap left where the domain sentence would go");
        assertFalse(HostingExpiryEmail.compose("o@e.com", "Summer", "   ", 7, MANAGE)
                .textBody().contains("live at"));
    }

    @Test
    @DisplayName("an unnamed page still reads as a sentence")
    void handlesAMissingPageName() {
        // Pages can be saved without a name. "Free hosting for \"\" ends" is not a sentence.
        String body = HostingExpiryEmail.compose("o@e.com", null, null, 7, MANAGE).textBody();

        assertTrue(body.contains("Your landing page"));
        assertFalse(body.contains("\"\""));
    }

    @Test
    @DisplayName("text-only, so user-supplied names need no escaping")
    void isTextOnly() {
        // Same reasoning as InvitationEmail: the page name is user-supplied, and an HTML body
        // would add an injection surface a plain link does not need.
        EmailPort.Message message =
                HostingExpiryEmail.compose("o@e.com", "Summer", null, 7, MANAGE);

        assertNull(message.htmlBody());
        assertEquals("o@e.com", message.to());
    }
}
