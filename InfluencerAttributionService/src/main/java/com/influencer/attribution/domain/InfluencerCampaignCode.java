package com.influencer.attribution.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "influencer_campaign_codes")
public class InfluencerCampaignCode {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;


    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "campaign_creator_id")
    private UUID campaignCreatorId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "code_type", nullable = false)
    private String codeType;

    @Column(name = "landing_url")
    private String landingUrl;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "marketplace_connection_id")
    private UUID marketplaceConnectionId;

    @Column(name = "discount_type")
    private String discountType;

    @Column(name = "discount_value")
    private java.math.BigDecimal discountValue;

    @Column(name = "commission_type")
    private String commissionType;

    @Column(name = "commission_value")
    private java.math.BigDecimal commissionValue;

    @Column(name = "channel")
    private String channel;

    @Column(name = "ref_slug")
    private String refSlug;

    @Column(name = "external_coupon_id")
    private String externalCouponId;

    @Column(name = "sync_status", nullable = false)
    private String syncStatus;

    @Column(name = "public_slug")
    private String publicSlug;

    @Column(name = "personal_blurb")
    private String personalBlurb;

    @Column(name = "embed_url")
    private String embedUrl;

    @Column(name = "personalization_status", nullable = false)
    private String personalizationStatus;

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
        if (codeType == null) {
            codeType = "discount";
        }
        if (isActive == null) {
            isActive = true;
        }
        if (metadata == null) {
            metadata = "{}";
        }
        if (syncStatus == null) {
            syncStatus = "local";
        }
        if (personalizationStatus == null) {
            personalizationStatus = "none";
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCodeType() {
        return codeType;
    }

    public void setCodeType(String codeType) {
        this.codeType = codeType;
    }

    public String getLandingUrl() {
        return landingUrl;
    }

    public void setLandingUrl(String landingUrl) {
        this.landingUrl = landingUrl;
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

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean active) {
        isActive = active;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
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

    public UUID getMarketplaceConnectionId() {
        return marketplaceConnectionId;
    }

    public void setMarketplaceConnectionId(UUID marketplaceConnectionId) {
        this.marketplaceConnectionId = marketplaceConnectionId;
    }

    public String getDiscountType() {
        return discountType;
    }

    public void setDiscountType(String discountType) {
        this.discountType = discountType;
    }

    public java.math.BigDecimal getDiscountValue() {
        return discountValue;
    }

    public void setDiscountValue(java.math.BigDecimal discountValue) {
        this.discountValue = discountValue;
    }

    public String getCommissionType() {
        return commissionType;
    }

    public void setCommissionType(String commissionType) {
        this.commissionType = commissionType;
    }

    public java.math.BigDecimal getCommissionValue() {
        return commissionValue;
    }

    public void setCommissionValue(java.math.BigDecimal commissionValue) {
        this.commissionValue = commissionValue;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getRefSlug() {
        return refSlug;
    }

    public void setRefSlug(String refSlug) {
        this.refSlug = refSlug;
    }

    public String getExternalCouponId() {
        return externalCouponId;
    }

    public void setExternalCouponId(String externalCouponId) {
        this.externalCouponId = externalCouponId;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public String getPublicSlug() {
        return publicSlug;
    }

    public void setPublicSlug(String publicSlug) {
        this.publicSlug = publicSlug;
    }

    public String getPersonalBlurb() {
        return personalBlurb;
    }

    public void setPersonalBlurb(String personalBlurb) {
        this.personalBlurb = personalBlurb;
    }

    public String getEmbedUrl() {
        return embedUrl;
    }

    public void setEmbedUrl(String embedUrl) {
        this.embedUrl = embedUrl;
    }

    public String getPersonalizationStatus() {
        return personalizationStatus;
    }

    public void setPersonalizationStatus(String personalizationStatus) {
        this.personalizationStatus = personalizationStatus;
    }
}
