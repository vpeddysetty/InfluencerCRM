package com.influencer.dao.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "creators")
public class Creator {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @Column(name = "handle", nullable = false)
    private String handle;

    @Column(name = "name")
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "platform", nullable = false)
    private String platform;

    @Column(name = "follower_count")
    private Integer followerCount;

    @Column(name = "engagement_rate")
    private BigDecimal engagementRate;

    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags;

    @Column(name = "notes")
    private String notes;

    @Column(name = "status")
    private String status;

    @Column(name = "country")
    private String country;

    @Column(name = "city")
    private String city;

    @Column(name = "timezone")
    private String timezone;

    @Column(name = "languages", columnDefinition = "text[]")
    private String[] languages;

    @Column(name = "niche")
    private String niche;

    @Column(name = "content_categories", columnDefinition = "text[]")
    private String[] contentCategories;

    @Column(name = "audience_demographics", columnDefinition = "jsonb")
    private String audienceDemographics;

    @Column(name = "audience_size_estimate")
    private Long audienceSizeEstimate;

    @Column(name = "average_views")
    private Long averageViews;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "source")
    private String source;

    @Column(name = "brand_safety_score")
    private BigDecimal brandSafetyScore;

    @Column(name = "safety_notes")
    private String safetyNotes;

    @Column(name = "preferred_rate")
    private BigDecimal preferredRate;

    @Column(name = "minimum_fee")
    private BigDecimal minimumFee;

    @Column(name = "currency")
    private String currency;

    @Column(name = "custom_attributes", columnDefinition = "jsonb")
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

    public UUID getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(UUID importBatchId) {
        this.importBatchId = importBatchId;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Integer getFollowerCount() {
        return followerCount;
    }

    public void setFollowerCount(Integer followerCount) {
        this.followerCount = followerCount;
    }

    public BigDecimal getEngagementRate() {
        return engagementRate;
    }

    public void setEngagementRate(BigDecimal engagementRate) {
        this.engagementRate = engagementRate;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String[] getLanguages() {
        return languages;
    }

    public void setLanguages(String[] languages) {
        this.languages = languages;
    }

    public String getNiche() {
        return niche;
    }

    public void setNiche(String niche) {
        this.niche = niche;
    }

    public String[] getContentCategories() {
        return contentCategories;
    }

    public void setContentCategories(String[] contentCategories) {
        this.contentCategories = contentCategories;
    }

    public String getAudienceDemographics() {
        return audienceDemographics;
    }

    public void setAudienceDemographics(String audienceDemographics) {
        this.audienceDemographics = audienceDemographics;
    }

    public Long getAudienceSizeEstimate() {
        return audienceSizeEstimate;
    }

    public void setAudienceSizeEstimate(Long audienceSizeEstimate) {
        this.audienceSizeEstimate = audienceSizeEstimate;
    }

    public Long getAverageViews() {
        return averageViews;
    }

    public void setAverageViews(Long averageViews) {
        this.averageViews = averageViews;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public BigDecimal getBrandSafetyScore() {
        return brandSafetyScore;
    }

    public void setBrandSafetyScore(BigDecimal brandSafetyScore) {
        this.brandSafetyScore = brandSafetyScore;
    }

    public String getSafetyNotes() {
        return safetyNotes;
    }

    public void setSafetyNotes(String safetyNotes) {
        this.safetyNotes = safetyNotes;
    }

    public BigDecimal getPreferredRate() {
        return preferredRate;
    }

    public void setPreferredRate(BigDecimal preferredRate) {
        this.preferredRate = preferredRate;
    }

    public BigDecimal getMinimumFee() {
        return minimumFee;
    }

    public void setMinimumFee(BigDecimal minimumFee) {
        this.minimumFee = minimumFee;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
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
