package com.influencer.webe.payout.provider;

import com.influencer.webe.payout.CreatorPayoutOnboardingPort;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * The shipped default: nobody needs onboarding (roadmap PR-47).
 *
 * <p>Correct rather than a placeholder. {@code ManualPayoutProvider} is the shipped payout rail, and
 * a brand paying a creator by bank transfer has no account to create — asking them to complete a
 * Stripe flow first would be a step that buys nothing.
 *
 * <p>Matches every other integration here: {@code EmailPort}'s log provider, {@code BillingProvider}
 * manual, {@code MockDomainRegistrar} — and, like {@code BillingProviderRegistry.active()}, it is
 * the fallback that keeps an unset or mistyped property from taking the application down.
 *
 * <p><b>Why a property and not {@code @ConditionalOnMissingBean} — this took production down.</b>
 * The original form was {@code @Component @ConditionalOnMissingBean(CreatorPayoutOnboardingPort.class)},
 * chosen so adding a rail needed no edit here. It cannot work on a scanned {@code @Component}:
 * {@code @ConditionalOnMissingBean} is built for {@code @Bean} methods in auto-configuration, which
 * Spring evaluates AFTER component scanning, when the set of beans is settled. During scanning the
 * answer depends on the order definitions happen to be registered in — so the same code kept this
 * bean locally and dropped it in the production image.
 *
 * <p>Dropped it, while {@code StripeConnectOnboarding} was also absent because
 * {@code web-experience.payout.onboarding.provider} was unset. Both implementations excluded, no
 * port, and {@code CreatorPayoutOnboardingService} failed to construct:
 * <em>APPLICATION FAILED TO START</em>. The whole BFF, over an optional payout rail nobody uses.
 *
 * <p>It stayed invisible from PR-47 shipping until 2026-09-03, because the running container was
 * never restarted — a Spot reclamation replaced the instance, boot ran for the first time since,
 * and the platform did not come back. The ASG reported the instance healthy throughout.
 *
 * <p><b>The condition is NEGATIVE on purpose.</b> It reads "unless someone asked for stripe", not
 * "when someone asked for manual". Matching {@code havingValue = "manual"} would leave a typo —
 * {@code stirpe} — matching nothing at all: this bean excluded, Stripe's own condition unmatched,
 * no port, and the same total outage from a single mistyped character. Written this way, anything
 * that is not exactly {@code stripe} lands here, which is the same fail-safe posture as
 * {@code BillingProviderRegistry.active()} falling back rather than throwing on an unknown key.
 *
 * <p>Adding a rail is still one new class plus setting the property to its key — and one line here,
 * which is the deliberate price of not being able to boot into a state with no port at all.
 */
@Component
@Conditional(NoOnboardingRequired.UnlessStripe.class)
public class NoOnboardingRequired implements CreatorPayoutOnboardingPort {

    /**
     * Matches unless the property names a rail that supplies its own port.
     *
     * <p>A hand-written {@link Condition} because {@code @ConditionalOnProperty} can only express
     * "equals", and what is needed here is "anything but" — including a value nobody anticipated.
     * The set below is the registry: a new rail adds its key here and its own {@code @Component},
     * and everything else — unset, blank, {@code manual}, a typo — resolves to this bean.
     */
    static final class UnlessStripe implements Condition {

        /** Keys whose own provider bean will supply the port. Keep in step with the rails. */
        private static final Set<String> RAILS_WITH_THEIR_OWN_PORT = Set.of("stripe");

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String configured = context.getEnvironment()
                    .getProperty("web-experience.payout.onboarding.provider", "");
            return !RAILS_WITH_THEIR_OWN_PORT.contains(configured.trim().toLowerCase(Locale.ROOT));
        }
    }

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
