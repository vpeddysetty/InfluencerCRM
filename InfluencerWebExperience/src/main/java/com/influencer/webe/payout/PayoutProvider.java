package com.influencer.webe.payout;

import java.math.BigDecimal;

/**
 * SPI for paying influencers their accrued commission (accounts-payable), mirror
 * of the marketplace SPI. Adding Stripe Connect / PayPal Payouts / Wise = one
 * {@code @Component} implementing this; {@link PayoutProviderRegistry} discovers it.
 */
public interface PayoutProvider {

    /** Stable key (e.g. "manual", "stripe", "paypal"). */
    String key();

    String displayName();

    /**
     * Execute a payout to a creator. For the manual provider this just records the
     * intent; real providers move money. Returns a provider reference + status.
     *
     * <p><b>{@code payoutId} is the idempotency key.</b> It is the id of the already-persisted
     * payout row, so it is unique per payout and stable across a retry of the same one. Real
     * providers must pass it as their idempotency token (Stripe {@code Idempotency-Key}, PayPal
     * {@code sender_batch_id}) so that a retry after a timeout settles once rather than twice —
     * the failure mode that costs real money and is invisible until reconciliation.
     *
     * <p>Derive the returned reference from this, never from {@code creatorId}: a creator is paid
     * many times, so a creator-derived reference collides on the second payout.
     */
    PayoutResult pay(String payoutId, String creatorId, BigDecimal amount, String currency, String note);

    /** Result of a payout attempt. */
    class PayoutResult {
        private final boolean success;
        private final String providerRef;
        private final String status;   // "paid" | "processing" | "failed"
        private final String message;

        private PayoutResult(boolean success, String providerRef, String status, String message) {
            this.success = success;
            this.providerRef = providerRef;
            this.status = status;
            this.message = message;
        }

        public static PayoutResult paid(String providerRef) {
            return new PayoutResult(true, providerRef, "paid", null);
        }

        public static PayoutResult failed(String message) {
            return new PayoutResult(false, null, "failed", message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getProviderRef() {
            return providerRef;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }
    }
}
