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
    public PayoutResult pay(String creatorId, BigDecimal amount, String currency, String note) {
        // No external call; generate a human-traceable reference.
        String ref = "manual-" + creatorId.substring(0, Math.min(8, creatorId.length()));
        return PayoutResult.paid(ref);
    }
}
