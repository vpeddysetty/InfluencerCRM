package com.influencer.webe.payout.provider;

import com.influencer.webe.payout.CreatorPayoutOnboardingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The shipped default: nobody needs onboarding (roadmap PR-47).
 *
 * <p>Correct rather than a placeholder. {@code ManualPayoutProvider} is the shipped payout rail, and
 * a brand paying a creator by bank transfer has no account to create — asking them to complete a
 * Stripe flow first would be a step that buys nothing.
 *
 * <p>Matches every other integration here: {@code EmailPort}'s log provider, {@code BillingProvider}
 * manual, {@code MockDomainRegistrar}. {@code @ConditionalOnMissingBean} rather than a property
 * value, so adding a rail is one new class and no edit here — and a deployment can never end up
 * with NO port and a null pointer at the moment somebody clicks Invite.
 */
@Component
@ConditionalOnMissingBean(CreatorPayoutOnboardingPort.class)
public class NoOnboardingRequired implements CreatorPayoutOnboardingPort {

    @Override
    public String key() {
        return "manual";
    }

    @Override
    public boolean isConfigured() {
        // Honest: there is nothing to configure, and nothing this can do. A caller checks this
        // before offering an onboarding button, so a brand on the manual rail never sees one.
        return false;
    }

    @Override
    public Onboarding start(String existingAccountId, String creatorEmail, String returnUrl, String refreshUrl) {
        return null;
    }

    @Override
    public Status status(String accountId) {
        // Null is UNKNOWN, not "not payable". On the manual rail whether a creator can be paid is a
        // fact about the brand's bank, which this application has no way to observe -- and claiming
        // false would tell a brand their own creator cannot be paid.
        return null;
    }
}
