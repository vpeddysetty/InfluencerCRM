package com.influencer.dao.content.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "landing_templates")
public class LandingTemplate {
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

    @Column(name = "public_slug", nullable = false)
    private String publicSlug;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "blocks", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String blocks;

    @Column(name = "theme", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String theme;

    /**
     * GrapesJS document { html, css }. Deliberately nullable with no default: NULL is
     * the signal that this page has never been opened in the visual builder, which is
     * what the renderer branches on to fall back to the typed-block `blocks` path.
     */
    @Column(name = "document", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String document;

    @Column(name = "status", nullable = false)
    private String status;

    /** Eight-value page lifecycle; `status` stays the two-value publish gate. */
    @Column(name = "stage", nullable = false)
    private String stage;

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
        if (name == null) {
            name = "Landing page";
        }
        if (blocks == null) {
            blocks = "[]";
        }
        if (theme == null) {
            theme = "{}";
        }
        if (status == null) {
            status = "draft";
        }
        if (stage == null) {
            stage = "draft";
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

    public String getPublicSlug() {
        return publicSlug;
    }

    public void setPublicSlug(String publicSlug) {
        this.publicSlug = publicSlug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBlocks() {
        return blocks;
    }

    public void setBlocks(String blocks) {
        this.blocks = blocks;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
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
