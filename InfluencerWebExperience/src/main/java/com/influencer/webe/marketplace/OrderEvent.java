package com.influencer.webe.marketplace;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical order/purchase event normalized from any marketplace's webhook or
 * poll payload. The Phase 3 attribution engine consumes only this shape, so a
 * new marketplace never touches attribution code.
 */
public class OrderEvent {
    private String externalOrderId;
    private String externalOrderLineId;
    private String couponCode;        // the discount code applied at checkout, if any
    private String customerExternalId;
    private BigDecimal saleAmount;
    private BigDecimal discountAmount;
    private String currency;
    private String status;            // "purchase" | "refunded" | "cancelled"
    private Instant occurredAt;

    public String getExternalOrderId() {
        return externalOrderId;
    }

    public void setExternalOrderId(String externalOrderId) {
        this.externalOrderId = externalOrderId;
    }

    public String getExternalOrderLineId() {
        return externalOrderLineId;
    }

    public void setExternalOrderLineId(String externalOrderLineId) {
        this.externalOrderLineId = externalOrderLineId;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public void setCouponCode(String couponCode) {
        this.couponCode = couponCode;
    }

    public String getCustomerExternalId() {
        return customerExternalId;
    }

    public void setCustomerExternalId(String customerExternalId) {
        this.customerExternalId = customerExternalId;
    }

    public BigDecimal getSaleAmount() {
        return saleAmount;
    }

    public void setSaleAmount(BigDecimal saleAmount) {
        this.saleAmount = saleAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
