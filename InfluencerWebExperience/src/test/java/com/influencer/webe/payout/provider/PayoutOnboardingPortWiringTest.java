package com.influencer.webe.payout.provider;

import com.influencer.webe.payout.CreatorPayoutOnboardingPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that SOME {@link CreatorPayoutOnboardingPort} always exists.
 *
 * <p><b>Why this test exists.</b> On 2026-09-03 production went fully down — every endpoint, not
 * just payouts — because no bean of this type could be constructed. {@code NoOnboardingRequired}
 * was a scanned {@code @Component} annotated {@code @ConditionalOnMissingBean}, which is only
 * dependable on {@code @Bean} methods in auto-configuration; during component scanning it resolved
 * on registration order and excluded itself in the production image. {@code StripeConnectOnboarding}
 * was absent too, its property being unset. {@code CreatorPayoutOnboardingService} then had no
 * constructor argument and the context failed to refresh.
 *
 * <p>The existing {@code CreatorPayoutOnboardingServiceTest} passed throughout: it injects a stub
 * port directly, so it proves the service's logic and says nothing about whether Spring can supply
 * the collaborator. That gap is what these cases close — they assert on the container, not on a
 * hand-wired object.
 *
 * <p>An {@link ApplicationContextRunner} rather than {@code @SpringBootTest} deliberately: it
 * evaluates real conditions against a real context, which is the mechanism under test, while
 * starting nothing else. A full boot would need a database and would hide this behind other
 * failures.
 */
class PayoutOnboardingPortWiringTest {

    /**
     * Only the provider package is scanned. Widening this would pull in beans needing a datasource
     * and turn a wiring assertion into an integration test that fails for unrelated reasons.
     */
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(ProviderScan.class);

    @Test
    @DisplayName("an unset property still yields a port — the production failure, asserted")
    void unsetPropertyStillYieldsAPort() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CreatorPayoutOnboardingPort.class);
            assertThat(context.getBean(CreatorPayoutOnboardingPort.class).key()).isEqualTo("manual");
        });
    }

    @Test
    @DisplayName("the shipped default of manual selects the no-onboarding rail")
    void manualSelectsNoOnboarding() {
        contexts.withPropertyValues("web-experience.payout.onboarding.provider=manual")
                .run(context -> {
                    assertThat(context).hasSingleBean(CreatorPayoutOnboardingPort.class);
                    assertThat(context).hasSingleBean(NoOnboardingRequired.class);
                });
    }

    @Test
    @DisplayName("an unknown provider key falls back rather than leaving the context portless")
    void unknownProviderFallsBack() {
        // The typo case. WEBE_BILLING_PROVIDER's registry deliberately falls back on an unknown key
        // rather than throwing, and the same reasoning applies harder here: a mistyped environment
        // variable must not be able to stop the application from starting.
        contexts.withPropertyValues("web-experience.payout.onboarding.provider=stirpe")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CreatorPayoutOnboardingPort.class);
                });
    }

    /**
     * Selecting a real rail must still leave exactly one port — not two, which would fail injection
     * just as surely as none, and not zero.
     */
    @Test
    @DisplayName("selecting stripe swaps the implementation, leaving exactly one port")
    void stripeSwapsTheImplementation() {
        contexts.withPropertyValues(
                        "web-experience.payout.onboarding.provider=stripe",
                        "web-experience.billing.stripe.secret-key=sk_test_wiring")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CreatorPayoutOnboardingPort.class);
                    assertThat(context).doesNotHaveBean(NoOnboardingRequired.class);
                });
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = NoOnboardingRequired.class)
    static class ProviderScan {

        /**
         * {@code StripeConnectOnboarding} collaborates with this. A real instance rather than a
         * mock: no case here makes a call, and Mockito is unreliable on this JDK (see the BFF
         * WebMvcTest notes). Timeouts are the production defaults.
         */
        @org.springframework.context.annotation.Bean
        com.influencer.webe.shared.infrastructure.OutboundHttpClient outboundHttpClient() {
            return new com.influencer.webe.shared.infrastructure.OutboundHttpClient(
                    new com.fasterxml.jackson.databind.ObjectMapper(), 5000, 10000);
        }
    }
}
