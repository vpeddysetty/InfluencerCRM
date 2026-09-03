package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.SocialPublishPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default publisher, and the shape of the port it implements (roadmap PR-45).
 *
 * <p>The third outcome is the design decision worth pinning. A binary real-or-manual result would
 * foreclose the path that is reachable TODAY without a publishing permission: TikTok's
 * {@code share/upload} and Instagram's draft flow put the asset and caption into the creator's own
 * composer. A port that cannot express that would force every future adapter to lie in one
 * direction or the other.
 */
class ManualSocialPublisherTest {

    private final ManualSocialPublisher publisher = new ManualSocialPublisher();

    @Test
    @DisplayName("the shipped default posts nothing and says so")
    void defaultIsManual() {
        SocialPublishPort.Result result = publisher.publish("caption", "https://x/a.png", "https://x/l");

        assertThat(result.outcome()).isEqualTo(SocialPublishPort.Outcome.MANUAL);
        assertThat(result.isFailure()).isFalse();
    }

    @Test
    @DisplayName("nothing tried is not the same as something failed")
    void manualIsNotAFailure() {
        // Conflating "we do not do this yet" with "we tried and it broke" would put an error in
        // front of a creator whose share kit is sitting there working perfectly.
        assertThat(publisher.publish(null, null, null).outcome())
                .isNotEqualTo(SocialPublishPort.Outcome.FAILED);
    }

    @Test
    @DisplayName("the port can express staged-for-confirmation, which is the whole point of three outcomes")
    void stagedOutcomeExists() {
        assertThat(SocialPublishPort.Outcome.valueOf("STAGED_FOR_USER_CONFIRMATION")).isNotNull();
    }

    @Test
    @DisplayName("a failure always carries a reason, so it can never reach a creator as a shrug")
    void failureAlwaysExplainsItself() {
        // A failed publish must surface as a failure -- only a failed READ may degrade to
        // simulation. Section 10.3 records why: the social registry falls back to fabricated
        // metrics by design, which is correct for vetting and a liar on a publish path.
        assertThat(SocialPublishPort.Result.failed("instagram", null).detail()).isNotBlank();
        assertThat(SocialPublishPort.Result.failed("instagram", "token expired").detail())
                .isEqualTo("token expired");
    }
}
