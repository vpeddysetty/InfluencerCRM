package com.influencer.dao.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "influencer_sale_attributions")
public class InfluencerSaleAttribution {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "campaign_code_id", nullable = false)
    private UUID campaignCodeId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "campaign_creator_id")
    private UUID campaignCreatorId;

    @Column(name = "platform", nullable = false)
    private String platform;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "order_line_id")
    private String orderLineId;

    @Column(name = "customer_external_id")
    private String customerExternalId;

    @Column(name = "sale_amount", nullable = false)
    private BigDecimal saleAmount;

    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    @Column(name = "net_amount")
    private BigDecimal netAmount;

    @Column(name = "commission_amount")
    private BigDecimal commissionAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "tracked_at", nullable = false)
    private Instant trackedAt;

    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

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
        if (platform == null) {
            platform = "direct";
        }
        if (status == null) {
            status = "pending";
        }
        if (currency == null) {
            currency = "USD";
        }
        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }
        if (occurredAt == null) {
            occurredAt = now;
        }
        if (trackedAt == null) {
            trackedAt = now;
        }
        if (rawPayload == null) {
            rawPayload = "{}";
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

    public UUID getCampaignCodeId() {
        return campaignCodeId;
    }

    public void setCampaignCodeId(UUID campaignCodeId) {
        this.campaignCodeId = campaignCodeId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID campaignId) {
        this.campaignId = campaignId;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(UUID creatorId) {
        this.creatorId = creatorId;
    }

    public UUID getCampaignCreatorId() {
        return campaignCreatorId;
    }

    public void setCampaignCreatorId(UUID campaignCreatorId) {
        this.campaignCreatorId = campaignCreatorId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderLineId() {
        return orderLineId;
    }

    public void setOrderLineId(String orderLineId) {
        this.orderLineId = orderLineId;
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

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(BigDecimal netAmount) {
        this.netAmount = netAmount;
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

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getTrackedAt() {
        return trackedAt;
    }

    public void setTrackedAt(Instant trackedAt) {
        this.trackedAt = trackedAt;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
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
