package com.influencer.webe.marketplace;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical, provider-agnostic description of a coupon to create in an external
 * marketplace. Adapters translate this to/from their vendor API; core code only
 * ever deals in this shape.
 */
public class CouponSpec {
    private String code;
    private String discountType;   // percent | fixed | free_shipping | bogo
    private BigDecimal discountValue;
    private Instant startsAt;
    private Instant endsAt;
    private Integer usageLimit;    // null = unlimited

    public CouponSpec() {
    }

    public CouponSpec(String code, String discountType, BigDecimal discountValue,
                      Instant startsAt, Instant endsAt, Integer usageLimit) {
        this.code = code;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.usageLimit = usageLimit;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDiscountType() {
        return discountType;
    }

    public void setDiscountType(String discountType) {
        this.discountType = discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public void setDiscountValue(BigDecimal discountValue) {
        this.discountValue = discountValue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public Integer getUsageLimit() {
        return usageLimit;
    }

    public void setUsageLimit(Integer usageLimit) {
        this.usageLimit = usageLimit;
    }
}
