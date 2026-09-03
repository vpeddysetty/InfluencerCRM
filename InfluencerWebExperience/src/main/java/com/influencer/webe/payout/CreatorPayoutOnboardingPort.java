package com.influencer.webe.payout;

/**
 * Getting a creator to the point where money can be sent to them (roadmap PR-47).
 *
 * <p><b>A port, like every other integration here.</b> The default does nothing and says so, which
 * is correct for a brand paying by bank transfer — the shipped {@code ManualPayoutProvider} needs no
 * onboarding at all, and most creators will never have a Connect account.
 *
 * <p><b>What this deliberately does NOT do: build the screens.</b> Stripe Connect Express hosts
 * identity, bank and tax collection on its own pages, and reimplementing them would mean handling a
 * creator's government ID and bank details in this application — taking on the compliance surface
 * the hosted flow exists to remove. This port produces a URL to send someone to, and reads back what
 * Stripe says about them afterwards.
 *
 * <p><b>Two facts, never one.</b> An account existing and an account being payable are days apart:
 * identity verification, the bank account and the tax form each gate it separately. Conflating them
 * is how someone promises a payout date they cannot keep — see §11.5 on not promising one before
 * `PR-49`.
 */
public interface CreatorPayoutOnboardingPort {

    /** Stable key — {@code manual}, {@code stripe}. */
    String key();

    /** Whether this implementation can actually onboard anyone; false for the no-op default. */
    boolean isConfigured();

    /**
     * Where the creator has to go, and who they are to the provider.
     *
     * @param accountId  the provider's account id — created on the first call, reused after
     * @param onboardingUrl a SINGLE-USE, short-lived URL. Stripe's account links expire in minutes
     *                      and cannot be reissued by reloading, so this is never something to store
     *                      or email; it is generated at the moment someone clicks.
     */
    record Onboarding(String accountId, String onboardingUrl) {}

    /**
     * What the provider says about an account now.
     *
     * @param payoutsEnabled whether money will actually move — the only field a brand should be
     *                       shown as "can be paid"
     * @param detail         what is still outstanding, when it is known, so the answer to "why not"
     *                       does not require logging into Stripe
     */
    record Status(String accountId, boolean payoutsEnabled, String detail) {}

    /**
     * Start or resume onboarding.
     *
     * <p>Resume is the common case, not the exception: identity checks stall, bank details get
     * mistyped, and a creator who abandoned the flow yesterday needs a fresh link to the SAME
     * account rather than a second one. Passing an existing {@code accountId} must therefore reuse
     * it — creating a duplicate would split one creator's payouts across two accounts.
     */
    Onboarding start(String existingAccountId, String creatorEmail, String returnUrl, String refreshUrl);

    /** Read current status. Never throws: an unreachable provider means "unknown", not "not payable". */
    Status status(String accountId);
}
