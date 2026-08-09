package com.influencer.webe.payout.provider;

import com.influencer.webe.payout.PayoutProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Records a payout as paid without moving money (the operator paid out-of-band,
 * e.g. PayPal/bank transfer, and is recording it). Ships the full payout workflow
 * with zero external integration — most small brands start exactly here.
 */
@Component
public class ManualPayoutProvider implements PayoutProvider {

    @Override
    public String key() {
        return "manual";
    }

    @Override
    public String displayName() {
        return "Manual / offline";
    }

    @Override
    public PayoutResult pay(String payoutId, String creatorId, BigDecimal amount,
                            String currency, String note) {
        // No external call; the reference is derived from the payout id, which is unique per
        // payout. It was previously derived from creatorId, which is not: every payout to the
        // same creator produced the identical reference "manual-1a2b3c4d", so an operator
        // reconciling a bank statement could not tell two payments apart, and any downstream
        // lookup by reference matched an arbitrary one of them.
        //
        // Deriving from the payout id also makes this idempotent for free: recording the same
        // payout twice yields the same reference rather than a second, indistinguishable one.
        if (payoutId == null || payoutId.isBlank()) {
            return PayoutResult.failed("No payout id — cannot generate a traceable reference");
        }
        return PayoutResult.paid("manual-" + payoutId);
    }
}
