package com.influencer.webe.marketplace;

/**
 * Result of creating a coupon in an external marketplace: the vendor's own id
 * plus a resolved status we store on {@code influencer_campaign_codes.sync_status}.
 */
public class ExternalCoupon {
    private final String externalId;
    private final String status; // "synced" | "sync_failed"

    public ExternalCoupon(String externalId, String status) {
        this.externalId = externalId;
        this.status = status;
    }

    public static ExternalCoupon synced(String externalId) {
        return new ExternalCoupon(externalId, "synced");
    }

    public String getExternalId() {
        return externalId;
    }

    public String getStatus() {
        return status;
    }
}
