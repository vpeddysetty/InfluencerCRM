package com.influencer.dao.creator.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;


    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @Column(name = "handle", nullable = false)
    private String handle;

    @Column(name = "name")
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "platform", nullable = false)
    @ColumnTransformer(write = "?::platform_type")
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
    @JdbcTypeCode(SqlTypes.JSON)
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

    // ---- Phase C: provenance ------------------------------------------
    // Metrics and classification carry SEPARATE provenance because they come from different
    // places: metrics are read from a platform, classification is produced by a model. Merging
    // them would lose the distinction the phase exists to preserve.

    /** platform_api | mock | manual | import. Null when no metrics were captured. */
    @Column(name = "metrics_source")
    private String metricsSource;

    @Column(name = "metrics_fetched_at")
    private Instant metricsFetchedAt;

    @Column(name = "metrics_platform_verified")
    private Boolean metricsPlatformVerified;

    /**
     * Stripe Connect Express account (roadmap PR-47, V52).
     *
     * <p>Its EXISTENCE means onboarding started, not that anyone can be paid — read
     * {@link #payoutsEnabled} for that. The two are days apart in practice, because identity, the
     * bank account and the tax form each gate payouts separately.
     */
    @Column(name = "stripe_account_id")
    private String stripeAccountId;

    /** Whether Stripe will actually move money. The only field to show a brand as "can be paid". */
    @Column(name = "payouts_enabled", nullable = false)
    private boolean payoutsEnabled = false;

    /**
     * When the provider last told us.
     *
     * <p>Not "when we asked": a cached boolean with no timestamp is a number nobody can judge —
     * minutes old it is fact, weeks old it is a guess, and the reader cannot tell which.
     */
    @Column(name = "payout_status_checked_at")
    private java.time.Instant payoutStatusCheckedAt;

    /**
     * When THIS platform decided a tax form is needed (roadmap PR-49).
     *
     * <p>Stored, where V52 refused to store Stripe's status, because it is not a cache of a Stripe
     * fact: it is the moment our own threshold arithmetic decided to withhold payment, and so is
     * evidence of why a payout was held.
     */
    @Column(name = "tax_form_required_at")
    private java.time.Instant taxFormRequiredAt;

    /**
     * When a brand recorded the form as received.
     *
     * <p>A brand assertion rather than a document: holding W-9s would make this application a
     * custodian of signed forms carrying SSNs. Consulted only on the MANUAL rail — where Connect is
     * in use, {@code payoutsEnabled} is authoritative.
     */
    @Column(name = "tax_form_on_file_at")
    private java.time.Instant taxFormOnFileAt;

    /** W-9 | W-8BEN. Free text, not an enum: a third country needs a value, not a migration. */
    @Column(name = "tax_form_kind")
    private String taxFormKind;

    /** llm | heuristic | manual. Never platform_api — a platform does not classify. */
    @Column(name = "classification_source")
    private String classificationSource;

    @Column(name = "classification_at")
    private Instant classificationAt;

    @Column(name = "content_themes", columnDefinition = "text[]")
    private String[] contentThemes;

    @Column(name = "risk_flags", columnDefinition = "text[]")
    private String[] riskFlags;

    @Column(name = "lead_source")
    private String leadSource;

    // ---- Phase C2: vetting -------------------------------------------
    // Distinct from `status` (active/inactive), which means whether the brand is currently
    // working with them — not whether they passed vetting.

    /** lead -> pending -> under_review -> approved | rejected. Only a human writes approved. */
    @Column(name = "vetting_status", nullable = false)
    private String vettingStatus;

    @Column(name = "vetting_decided_at")
    private Instant vettingDecidedAt;

    /** Null when a rule decided; set when a human did. That pair is how the two are told apart. */
    @Column(name = "vetting_decided_by_user_id")
    private UUID vettingDecidedByUserId;

    @Column(name = "lead_landing_template_id")
    private UUID leadLandingTemplateId;

    @Column(name = "preferred_rate")
    private BigDecimal preferredRate;

    @Column(name = "minimum_fee")
    private BigDecimal minimumFee;

    @Column(name = "currency")
    private String currency;

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

    // ---- Phase C accessors --------------------------------------------

    public String getMetricsSource() {
        return metricsSource;
    }

    public void setMetricsSource(String metricsSource) {
        this.metricsSource = metricsSource;
    }

    public Instant getMetricsFetchedAt() {
        return metricsFetchedAt;
    }

    public void setMetricsFetchedAt(Instant metricsFetchedAt) {
        this.metricsFetchedAt = metricsFetchedAt;
    }

    public Boolean getMetricsPlatformVerified() {
        return metricsPlatformVerified;
    }

    public void setMetricsPlatformVerified(Boolean metricsPlatformVerified) {
        this.metricsPlatformVerified = metricsPlatformVerified;
    }

    public String getClassificationSource() {
        return classificationSource;
    }

    public void setClassificationSource(String classificationSource) {
        this.classificationSource = classificationSource;
    }

    public Instant getClassificationAt() {
        return classificationAt;
    }

    public void setClassificationAt(Instant classificationAt) {
        this.classificationAt = classificationAt;
    }

    public String[] getContentThemes() {
        return contentThemes;
    }

    public void setContentThemes(String[] contentThemes) {
        this.contentThemes = contentThemes;
    }

    public String[] getRiskFlags() {
        return riskFlags;
    }

    public void setRiskFlags(String[] riskFlags) {
        this.riskFlags = riskFlags;
    }

    public String getLeadSource() {
        return leadSource;
    }

    public void setLeadSource(String leadSource) {
        this.leadSource = leadSource;
    }

    public UUID getLeadLandingTemplateId() {
        return leadLandingTemplateId;
    }

    public void setLeadLandingTemplateId(UUID leadLandingTemplateId) {
        this.leadLandingTemplateId = leadLandingTemplateId;
    }

    public String getVettingStatus() {
        return vettingStatus;
    }

    public void setVettingStatus(String vettingStatus) {
        this.vettingStatus = vettingStatus;
    }

    public Instant getVettingDecidedAt() {
        return vettingDecidedAt;
    }

    public void setVettingDecidedAt(Instant vettingDecidedAt) {
        this.vettingDecidedAt = vettingDecidedAt;
    }

    public UUID getVettingDecidedByUserId() {
        return vettingDecidedByUserId;
    }

    public void setVettingDecidedByUserId(UUID vettingDecidedByUserId) {
        this.vettingDecidedByUserId = vettingDecidedByUserId;
    }

    public String getStripeAccountId() {
        return stripeAccountId;
    }

    public void setStripeAccountId(String stripeAccountId) {
        this.stripeAccountId = stripeAccountId;
    }

    public boolean isPayoutsEnabled() {
        return payoutsEnabled;
    }

    public void setPayoutsEnabled(boolean payoutsEnabled) {
        this.payoutsEnabled = payoutsEnabled;
    }

    public java.time.Instant getPayoutStatusCheckedAt() {
        return payoutStatusCheckedAt;
    }

    public void setPayoutStatusCheckedAt(java.time.Instant payoutStatusCheckedAt) {
        this.payoutStatusCheckedAt = payoutStatusCheckedAt;
    }

    public java.time.Instant getTaxFormRequiredAt() {
        return taxFormRequiredAt;
    }

    public void setTaxFormRequiredAt(java.time.Instant taxFormRequiredAt) {
        this.taxFormRequiredAt = taxFormRequiredAt;
    }

    public java.time.Instant getTaxFormOnFileAt() {
        return taxFormOnFileAt;
    }

    public void setTaxFormOnFileAt(java.time.Instant taxFormOnFileAt) {
        this.taxFormOnFileAt = taxFormOnFileAt;
    }

    public String getTaxFormKind() {
        return taxFormKind;
    }

    public void setTaxFormKind(String taxFormKind) {
        this.taxFormKind = taxFormKind;
    }
}
