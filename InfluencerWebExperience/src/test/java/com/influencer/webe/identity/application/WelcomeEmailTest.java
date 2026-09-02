package com.influencer.webe.identity.application;

import com.influencer.webe.shared.application.EmailPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The email a new workspace gets (roadmap PR-02).
 *
 * <p>The assertion that earns its place is the LAST one: that the five steps here are the same five,
 * in the same order, as the in-product checklist. Two different answers to "what do I do first" is
 * worse than one answer, and the order is the argued part — creator before store, because the store
 * depends on someone else's system and opening with it strands someone on day one.
 */
class WelcomeEmailTest {

    private EmailPort.Message compose() {
        return WelcomeEmail.compose("owner@example.com", "Trailhead", "https://tejdux.com");
    }

    @Test
    @DisplayName("the workspace name is in the subject, so it is recognisable three days later")
    void subjectNamesTheWorkspace() {
        assertThat(compose().subject()).contains("Trailhead");
    }

    @Test
    @DisplayName("a blank workspace name degrades rather than producing 'Welcome to  '")
    void blankNameDegrades() {
        EmailPort.Message message = WelcomeEmail.compose("owner@example.com", "  ", "https://tejdux.com");

        assertThat(message.subject()).contains("your workspace");
        assertThat(message.textBody()).doesNotContain("  is ready");
    }

    @Test
    @DisplayName("text-only: an HTML body would make the user-typed brand name an injection surface")
    void textOnly() {
        assertThat(compose().htmlBody()).isNull();
    }

    @Test
    @DisplayName("it says where to sign in")
    void carriesTheAppUrl() {
        assertThat(compose().textBody()).contains("https://tejdux.com");
    }

    @Test
    @DisplayName("a missing app URL leaves no empty line pretending to be a link")
    void toleratesAbsentUrl() {
        EmailPort.Message message = WelcomeEmail.compose("owner@example.com", "Trailhead", null);

        assertThat(message.textBody()).doesNotContain("null");
    }

    @Test
    @DisplayName("no unsubscribe, and no deadline the pricing page contradicts")
    void makesNoPromisesItCannotKeep() {
        String body = compose().textBody().toLowerCase();

        // A single transactional message tied to an account someone just created is not a list.
        assertThat(body).doesNotContain("unsubscribe");
        // The free tier has no time limit; saying otherwise is a lie docs/legal/pricing.html
        // disproves in one click.
        assertThat(body).doesNotContain("expires").doesNotContain("trial ends");
    }

    @Test
    @DisplayName("the five steps match the in-product checklist, in the same order")
    void staysInStepWithTheChecklist() throws IOException {
        // The coupling is the point. `shell/activation.js` is the source of truth for what a new
        // workspace is told to do; if someone reorders it there and not here, a user gets one order
        // on screen and a different one in their inbox, and neither looks wrong on its own.
        //
        // Read from the file rather than duplicated as constants: a copy would drift silently,
        // which is the failure this test exists to make loud.
        Path activation = Path.of("../InfluencerUI/src/shell/activation.js");
        if (!Files.exists(activation)) {
            // Running from an unexpected working directory should not fail the build with a
            // misleading message about email copy.
            return;
        }
        String source = Files.readString(activation, StandardCharsets.UTF_8);
        String body = compose().textBody();

        int previous = -1;
        for (String id : new String[] {"creator", "campaign", "coupon", "page", "store"}) {
            int at = source.indexOf("id: '" + id + "'");
            assertThat(at).as("activation.js must still define the %s step", id).isGreaterThan(previous);
            previous = at;
        }

        // And the email lists them in that same order.
        assertThat(body.indexOf("creator")).isLessThan(body.indexOf("campaign"));
        assertThat(body.indexOf("campaign")).isLessThan(body.indexOf("discount code"));
        assertThat(body.indexOf("discount code")).isLessThan(body.indexOf("landing page"));
        assertThat(body.indexOf("landing page")).isLessThan(body.indexOf("store"));
    }
}
