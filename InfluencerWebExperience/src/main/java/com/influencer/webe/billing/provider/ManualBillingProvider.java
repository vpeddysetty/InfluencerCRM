package com.influencer.webe.billing.provider;

import com.influencer.webe.billing.BillingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records a subscription without taking any money.
 *
 * <p><b>What this is for.</b> An operator arranged payment out of band — an invoice, a bank
 * transfer, a founder-comped account — and is recording that fact. It is also the default while no
 * payment provider is configured, so the whole subscription path (schema, state machine, pause,
 * cancel, UI) is buildable and reviewable before a Stripe account exists.
 *
 * <p><b>It never claims to have charged anyone.</b> {@code capabilities().chargesMoney()} is
 * false, {@code key()} is {@code "manual"}, and both are recorded on every subscription row. This
 * is the rule {@code MockDomainRegistrar} and {@code LoggingEmailSender} follow, and it matters
 * more here than anywhere else in the codebase: a payment adapter that reported success would put
 * a simulated result about someone's money in front of them. An account that was never charged
 * must be distinguishable from one that was, forever, in the data.
 *
 * <p><b>Activation is immediate</b>, because there is no payment step to wait for. That is the one
 * behaviour a real provider genuinely differs on: hosted checkout returns a URL and activates only
 * on its webhook, once the user has actually paid.
 */
@Component
public class ManualBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(ManualBillingProvider.class);

    private static final String PROVIDER = "manual";

    @Override
    public String key() {
        return PROVIDER;
    }

    @Override
    public String displayName() {
        return "Manual / invoiced";
    }

    @Override
    public Capabilities capabilities() {
        // chargesMoney=false is the important one. Nothing downstream may present a subscription
        // created here as evidence of payment.
        return new Capabilities(false, false, false);
    }

    @Override
    public CheckoutSession startCheckout(String idempotencyKey, String accountId, String plan,
                                         String successUrl, String cancelUrl) {
        // WARN, not INFO: in a deployed environment this means someone is on a paid plan that no
        // payment provider is billing, which is a thing an operator should have to notice.
        log.warn("[billing:{}] Subscription to plan '{}' recorded for account {} WITHOUT any charge. "
                        + "No payment provider is configured — set web-experience.billing.provider.",
                PROVIDER, plan, accountId);

        return new CheckoutSession(
                // No URL: there is nowhere to send the user, because nothing is being collected.
                // Returning a fake one would be the failure this class exists to avoid.
                null,
                // Derived from the idempotency key, so recording the same subscription twice
                // yields the same reference — the same reasoning as ManualPayoutProvider.
                idempotencyKey == null || idempotencyKey.isBlank() ? null : PROVIDER + "-" + idempotencyKey,
                true,
                "Recorded without payment. No money was taken and no card was collected.");
    }

    @Override
    public Result cancel(String providerRef) {
        return Result.ok(PROVIDER, "Cancellation recorded. Nothing was being billed.");
    }

    @Override
    public Result pause(String providerRef) {
        return Result.ok(PROVIDER, "Pause recorded. Nothing was being billed.");
    }

    @Override
    public Result resume(String providerRef) {
        return Result.ok(PROVIDER, "Resume recorded. Nothing is being billed.");
    }
}
