package com.influencer.finance.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "influencer_commissions")
public class InfluencerCommission {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;


    @Column(name = "attribution_id")
    private UUID attributionId;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "gross_sale", nullable = false)
    private BigDecimal grossSale;

    @Column(name = "commission_amount", nullable = false)
    private BigDecimal commissionAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "payout_id")
    private UUID payoutId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (grossSale == null) {
            grossSale = BigDecimal.ZERO;
        }
        if (commissionAmount == null) {
            commissionAmount = BigDecimal.ZERO;
        }
        if (currency == null) {
            currency = "USD";
        }
        if (status == null) {
            status = "pending";
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getBrandId() {
        return brandId;
    }

    public void setBrandId(UUID brandId) {
        this.brandId = brandId;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public UUID getAttributionId() {
        return attributionId;
    }

    public void setAttributionId(UUID attributionId) {
        this.attributionId = attributionId;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(UUID creatorId) {
        this.creatorId = creatorId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID campaignId) {
        this.campaignId = campaignId;
    }

    public BigDecimal getGrossSale() {
        return grossSale;
    }

    public void setGrossSale(BigDecimal grossSale) {
        this.grossSale = grossSale;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public void setCommissionAmount(BigDecimal commissionAmount) {
        this.commissionAmount = commissionAmount;
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

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public UUID getPayoutId() {
        return payoutId;
    }

    public void setPayoutId(UUID payoutId) {
        this.payoutId = payoutId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
