package com.influencer.webe.billing;

/**
 * SPI for taking money <em>in</em> — subscriptions (roadmap M2.1/M2.2).
 *
 * <p><b>Not to be confused with {@code PayoutProvider}</b>, which pays money <em>out</em> to
 * creators. The roadmap is explicit that these are two different Stripe projects and must not be
 * conflated: one is Stripe Checkout + Billing, the other is Stripe Connect, with different
 * onboarding, different compliance, and different failure modes.
 *
 * <p><b>Why a port when no real provider exists yet.</b> The whole subscription path — schema,
 * state machine, pause, cancel, webhooks, UI — is buildable and testable without payment
 * credentials, and gating it on a Stripe account would mean none of it could be reviewed until
 * that account existed. Adding Stripe is then one {@code @Component} implementing this interface,
 * discovered by {@link BillingProviderRegistry} through {@code List<T>} injection, exactly as
 * {@code PayoutProviderRegistry} and {@code SocialProfileGateway} already work.
 *
 * <p><b>An implementation that takes no money must say so.</b> {@link #key()} is recorded on every
 * subscription and invoice, and {@link #capabilities()} states plainly whether a real charge can
 * happen. {@code ManualBillingProvider} returns {@code chargesMoney() == false}, so nothing in the
 * product can present an unpaid subscription as a paid one. This is the same rule
 * {@code MockDomainRegistrar} and {@code LoggingEmailSender} follow, and it is the one that keeps
 * honest mocking honest: a payment mock that reported success would be a simulated result about
 * someone's money.
 *
 * <p><b>No card data crosses this interface.</b> No PAN, CVV, expiry, or raw payment method
 * appears in any method here or in any schema behind it. Real providers collect those on their own
 * hosted pages — which is the entire reason to use hosted checkout — and hand back an opaque
 * reference.
 */
public interface BillingProvider {

    /** Stable key recorded on subscriptions and invoices: {@code "manual"}, {@code "stripe"}, … */
    String key();

    String displayName();

    /**
     * What this implementation can actually do.
     *
     * @param chargesMoney     whether a real charge reaches a real payment network. FALSE for any
     *                         manual or simulated implementation — the flag that stops the product
     *                         claiming an account paid when nothing was taken
     * @param hostedCheckout   whether {@link #startCheckout} returns a URL to send the user to
     * @param hostedPortal     whether the provider hosts its own manage-subscription page. The
     *                         roadmap's instruction for 2.1 is "hosted checkout, hosted portal — do
     *                         not build billing UI", so a provider offering one should be used
     *                         rather than reimplemented
     * @param expiresTrials    whether the provider ENDS a trial on its own and tells us. A trial
     *                         is the only subscription state that grants paid limits with no
     *                         payment behind it, so something must eventually end it. Stripe does:
     *                         it transitions the subscription and emits
     *                         {@code customer.subscription.updated}, which
     *                         {@code BillingWebhookService} already applies. A provider that
     *                         cannot must not be offered a trial at all — otherwise
     *                         {@code trialing} grants the paid plan forever, because
     *                         {@code SubscriptionState} treats it as entitled and nothing else
     *                         ever revisits it. Guarded in {@code SubscriptionService.subscribe}
     */
    record Capabilities(boolean chargesMoney, boolean hostedCheckout, boolean hostedPortal,
                        boolean expiresTrials) {
    }

    Capabilities capabilities();

    /**
     * Begins a subscription to {@code plan}.
     *
     * <p>{@code idempotencyKey} is the subscription row's id, generated and persisted before this
     * is called — the same discipline {@code PayoutProvider.pay} follows and for the same reason: a
     * retry after a timeout must not create a second subscription and a second charge. A key
     * generated inside the provider would differ on the retry and defeat the purpose.
     *
     * @return where to send the user, and what to record
     */
    CheckoutSession startCheckout(String idempotencyKey, String accountId, String plan,
                                  BillingInterval interval, String successUrl, String cancelUrl);

    /**
     * How often a subscription renews.
     *
     * <p>Separate from the plan because it is a different question with different consequences:
     * the plan decides what an account may do ({@code PlanPolicy}), the interval only decides how
     * often it is billed. Collapsing them into four plan keys would double {@code PlanPolicy} and
     * put a billing concern inside an entitlement enum.
     *
     * <p>{@link #MONTHLY} is the default everywhere. A caller that omits the interval gets billed
     * monthly, which is the smaller commitment — defaulting to a year would charge someone twelve
     * times what they expected on a parameter they never sent.
     */
    enum BillingInterval {
        MONTHLY("monthly"),
        YEARLY("yearly");

        private final String key;

        BillingInterval(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** Fails to {@link #MONTHLY} on anything unrecognised, for the reason given above. */
        public static BillingInterval forKey(String value) {
            if (value == null || value.isBlank()) {
                return MONTHLY;
            }
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            for (BillingInterval candidate : values()) {
                if (candidate.key.equals(normalized)) {
                    return candidate;
                }
            }
            // Also accept Stripe's own words, so a value echoed back from a provider payload
            // resolves rather than silently becoming monthly.
            if ("year".equals(normalized) || "annual".equals(normalized)) {
                return YEARLY;
            }
            if ("month".equals(normalized)) {
                return MONTHLY;
            }
            return MONTHLY;
        }
    }

    /**
     * @param checkoutUrl  where to send the user to pay, or null when the provider takes no money
     * @param providerRef  the provider's id for the resulting subscription, if it has one yet
     * @param activated    whether the subscription is live NOW. True only for providers that do
     *                     not need a payment step; a hosted-checkout provider returns false and
     *                     waits for its webhook, because the user has not paid yet
     */
    record CheckoutSession(String checkoutUrl, String providerRef, boolean activated, String detail) {
    }

    /**
     * How many days of trial {@link #startCheckout} asks for on {@code plan}, or 0 for none.
     *
     * <p><b>Per plan, not per provider.</b> Whether a tier gets a trial is a pricing decision that
     * differs between tiers: a buyer who cannot evaluate a plan inside the free tier's limits needs
     * one, and a plan that converts without a trial should not give away a month.
     *
     * <p>Defaults to 0 so a provider must opt in: a trial grants paid limits with nothing paid,
     * and the safe answer for an implementation that has not considered the question is that it
     * offers none. Only meaningful when {@link Capabilities#expiresTrials()} is true — a length
     * without something to enforce it is how {@code trialing} becomes permanent.
     */
    default int trialDays(String plan) {
        return 0;
    }

    /**
     * A link to the provider's own subscription-management page, or null if it hosts none.
     *
     * <p>Returning a URL here is what lets the product avoid building card-management UI it would
     * otherwise have to secure and keep in step with a provider's rules.
     */
    default String portalUrl(String providerRef, String returnUrl) {
        return null;
    }

    /**
     * Stops billing at the end of the paid period.
     *
     * <p><b>Not immediately.</b> Time already paid for is not confiscated; the subscription stays
     * active until its period ends. That distinction is the product feature the roadmap names —
     * it is what makes "no lock-in" checkable rather than claimed, against a competitor whose
     * most-cited complaint is cancellation being "impossible to stop".
     */
    Result cancel(String providerRef);

    /**
     * Suspends billing, keeping the subscription resumable.
     *
     * <p>Distinct from cancel: a paused account keeps its data and its plan on record, and resumes
     * without re-subscribing. A provider that cannot pause should say so through {@link Result}
     * rather than silently cancelling — those are very different things to do to a customer.
     */
    Result pause(String providerRef);

    Result resume(String providerRef);

    /**
     * The outcome of a change.
     *
     * <p>Reported rather than thrown, matching {@code EmailPort} and {@code PayoutProvider}: a
     * provider outage must not roll back a state change the caller has already decided on, and a
     * caller that does not care is not forced into a try/catch.
     */
    record Result(boolean ok, String provider, String detail) {

        public static Result ok(String provider, String detail) {
            return new Result(true, provider, detail);
        }

        public static Result failed(String provider, String detail) {
            return new Result(false, provider, detail);
        }
    }
}
