package com.influencer.dao.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A creator's own claim that they posted a page (roadmap PR-45, V50).
 *
 * <p><b>A claim, not a measurement</b>, and the class name says so rather than implying otherwise.
 * Nothing in this product can see a creator's feed: {@code business_discovery} is gated behind
 * Meta Advanced Access, and even approved it reads metrics rather than confirming that a specific
 * post exists. When {@code PR-46} lands a real adapter, a platform-verified post is a DIFFERENT row
 * carrying a platform id — the two must stay distinguishable, because a later reader treating a
 * self-report as verified is exactly the confusion this naming avoids.
 *
 * <p><b>Append-only</b>, like {@link PageHandoff}. A creator who posts, deletes and reposts has
 * done two things, and there is no update path here.
 *
 * <p>Unqualified table name, per the schema-per-context arrangement: the role's {@code search_path}
 * resolves {@code content} before {@code shared} and {@code public}, so qualifying it here would
 * break the mechanism rather than clarify it.
 */
@Entity
@Table(name = "share_posts")
public class SharePost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "landing_template_id", nullable = false)
    private UUID landingTemplateId;

    /**
     * Which creator's code was shared.
     *
     * <p>The kit is per-coupon because the link and the code both are, so this is what ties a claim
     * to one creator rather than to the page in general — a page shared by six creators would
     * otherwise have six indistinguishable claims.
     */
    @Column(name = "campaign_code_id", nullable = false)
    private UUID campaignCodeId;

    /** Set when the creator reports it through the portal, where they hold no user row. */
    @Column(name = "creator_identity_id")
    private UUID creatorIdentityId;

    /** Set when a brand records it on the creator's behalf. Exactly one of the two is expected. */
    @Column(name = "reported_by_user_id")
    private UUID reportedByUserId;

    /** Free text: a label on a self-report, not something a rule runs on. */
    @Column(name = "platform")
    private String platform;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getBrandId() {
        return brandId;
    }

    public void setBrandId(UUID brandId) {
        this.brandId = brandId;
    }

    public UUID getLandingTemplateId() {
        return landingTemplateId;
    }

    public void setLandingTemplateId(UUID landingTemplateId) {
        this.landingTemplateId = landingTemplateId;
    }

    public UUID getCampaignCodeId() {
        return campaignCodeId;
    }

    public void setCampaignCodeId(UUID campaignCodeId) {
        this.campaignCodeId = campaignCodeId;
    }

    public UUID getCreatorIdentityId() {
        return creatorIdentityId;
    }

    public void setCreatorIdentityId(UUID creatorIdentityId) {
        this.creatorIdentityId = creatorIdentityId;
    }

    public UUID getReportedByUserId() {
        return reportedByUserId;
    }

    public void setReportedByUserId(UUID reportedByUserId) {
        this.reportedByUserId = reportedByUserId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
