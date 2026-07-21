package com.influencer.dao.model;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "campaigns")
public class Campaign {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "goal")
    private String goal;

    @Column(name = "product")
    private String product;

    @Column(name = "budget")
    private BigDecimal budget;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "status", nullable = false)
    @ColumnTransformer(write = "?::campaign_status")
    private String status;

    @Column(name = "campaign_type")
    private String campaignType;

    @Column(name = "objective")
    private String objective;

    @Column(name = "target_audience")
    private String targetAudience;

    @Column(name = "market_region")
    private String marketRegion;

    @Column(name = "geo_targeting")
    private String geoTargeting;

    @Column(name = "deliverables_required", columnDefinition = "text[]")
    private String[] deliverablesRequired;

    @Column(name = "kpi_target")
    private String kpiTarget;

    @Column(name = "currency")
    private String currency;

    @Column(name = "priority")
    private String priority;

    @Column(name = "brief_url")
    private String briefUrl;

    @Column(name = "brief_notes")
    private String briefNotes;

    @Column(name = "content_guidelines")
    private String contentGuidelines;

    @Column(name = "campaign_owner")
    private String campaignOwner;

    @Column(name = "custom_attributes", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String customAttributes;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public BigDecimal getBudget() {
        return budget;
    }

    public void setBudget(BigDecimal budget) {
        this.budget = budget;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCampaignType() {
        return campaignType;
    }

    public void setCampaignType(String campaignType) {
        this.campaignType = campaignType;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    public String getTargetAudience() {
        return targetAudience;
    }

    public void setTargetAudience(String targetAudience) {
        this.targetAudience = targetAudience;
    }

    public String getMarketRegion() {
        return marketRegion;
    }

    public void setMarketRegion(String marketRegion) {
        this.marketRegion = marketRegion;
    }

    public String getGeoTargeting() {
        return geoTargeting;
    }

    public void setGeoTargeting(String geoTargeting) {
        this.geoTargeting = geoTargeting;
    }

    public String[] getDeliverablesRequired() {
        return deliverablesRequired;
    }

    public void setDeliverablesRequired(String[] deliverablesRequired) {
        this.deliverablesRequired = deliverablesRequired;
    }

    public String getKpiTarget() {
        return kpiTarget;
    }

    public void setKpiTarget(String kpiTarget) {
        this.kpiTarget = kpiTarget;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getBriefUrl() {
        return briefUrl;
    }

    public void setBriefUrl(String briefUrl) {
        this.briefUrl = briefUrl;
    }

    public String getBriefNotes() {
        return briefNotes;
    }

    public void setBriefNotes(String briefNotes) {
        this.briefNotes = briefNotes;
    }

    public String getContentGuidelines() {
        return contentGuidelines;
    }

    public void setContentGuidelines(String contentGuidelines) {
        this.contentGuidelines = contentGuidelines;
    }

    public String getCampaignOwner() {
        return campaignOwner;
    }

    public void setCampaignOwner(String campaignOwner) {
        this.campaignOwner = campaignOwner;
    }

    public String getCustomAttributes() {
        return customAttributes;
    }

    public void setCustomAttributes(String customAttributes) {
        this.customAttributes = customAttributes;
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
