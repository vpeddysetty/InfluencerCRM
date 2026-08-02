package com.influencer.attribution.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "daily_attribution_stats")
public class DailyAttributionStat {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;


    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "creator_id")
    private UUID creatorId;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "channel")
    private String channel;

    @Column(name = "clicks", nullable = false)
    private Integer clicks;

    @Column(name = "orders", nullable = false)
    private Integer orders;

    @Column(name = "gross_sales", nullable = false)
    private BigDecimal grossSales;

    @Column(name = "discounts", nullable = false)
    private BigDecimal discounts;

    @Column(name = "commission", nullable = false)
    private BigDecimal commission;

    @Column(name = "refunds", nullable = false)
    private BigDecimal refunds;

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
        if (clicks == null) {
            clicks = 0;
        }
        if (orders == null) {
            orders = 0;
        }
        if (grossSales == null) {
            grossSales = BigDecimal.ZERO;
        }
        if (discounts == null) {
            discounts = BigDecimal.ZERO;
        }
        if (commission == null) {
            commission = BigDecimal.ZERO;
        }
        if (refunds == null) {
            refunds = BigDecimal.ZERO;
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

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
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

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Integer getClicks() {
        return clicks;
    }

    public void setClicks(Integer clicks) {
        this.clicks = clicks;
    }

    public Integer getOrders() {
        return orders;
    }

    public void setOrders(Integer orders) {
        this.orders = orders;
    }

    public BigDecimal getGrossSales() {
        return grossSales;
    }

    public void setGrossSales(BigDecimal grossSales) {
        this.grossSales = grossSales;
    }

    public BigDecimal getDiscounts() {
        return discounts;
    }

    public void setDiscounts(BigDecimal discounts) {
        this.discounts = discounts;
    }

    public BigDecimal getCommission() {
        return commission;
    }

    public void setCommission(BigDecimal commission) {
        this.commission = commission;
    }

    public BigDecimal getRefunds() {
        return refunds;
    }

    public void setRefunds(BigDecimal refunds) {
        this.refunds = refunds;
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
