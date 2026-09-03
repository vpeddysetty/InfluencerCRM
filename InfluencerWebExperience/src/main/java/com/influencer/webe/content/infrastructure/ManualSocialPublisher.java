package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.SocialPublishPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The shipped default: nothing is sent anywhere (roadmap PR-45).
 *
 * <p>Matches the pattern every integration in this codebase uses — {@code EmailPort}'s log
 * provider, {@code BillingProvider}'s manual one, {@code MockDomainRegistrar} — a safe no-op that
 * is honest about being one. A creator using this gets the share kit and posts from their phone,
 * which is what creators do anyway.
 *
 * <p><b>{@code @ConditionalOnMissingBean}, not a property.</b> The other registries here select by
 * name because they have several real implementations to choose between; this has exactly one
 * possible successor per platform, and PR-46 adds them as {@code @ConditionalOnProperty} beans.
 * Selecting by absence means adding an adapter is one new class and no edit here — and, more
 * usefully, means a deployment can never end up with NO publisher and a null pointer at the moment
 * a creator presses share.
 */
@Component
@ConditionalOnMissingBean(SocialPublishPort.class)
public class ManualSocialPublisher implements SocialPublishPort {

    @Override
    public String platform() {
        return "manual";
    }

    /**
     * Reports MANUAL without attempting anything.
     *
     * <p>Deliberately not FAILED: nothing was tried, so nothing failed. Conflating "we do not do
     * this yet" with "we tried and it broke" would put an error in front of a creator whose share
     * kit is sitting there working perfectly.
     */
    @Override
    public Result publish(String caption, String assetUrl, String linkUrl) {
        return Result.manual(platform());
    }
}
