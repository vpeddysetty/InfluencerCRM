package com.influencer.content.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * An append-only snapshot of a landing page at the moment it was saved (roadmap A.5).
 *
 * Snapshots, not diffs: at landing-page volume the storage cost is irrelevant, and
 * restoring a snapshot is trivially correct in a way that replaying diffs is not.
 *
 * There is deliberately no update or delete path — a version records what the page
 * looked like at a point in time, and rewriting that would defeat the purpose of
 * keeping it. This is what makes Phase G co-editing safe without a CRDT: an overwrite
 * is always recoverable.
 */
@Entity
@Table(name = "landing_template_versions")
public class LandingTemplateVersion {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "landing_template_id", nullable = false)
    private UUID landingTemplateId;

    /** Denormalized so history stays tenant-filterable even if the template is deleted. */
    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "name")
    private String name;

    @Column(name = "document", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String document;

    @Column(name = "blocks", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String blocks;

    @Column(name = "theme", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String theme;

    @Column(name = "stage")
    private String stage;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
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

    public UUID getLandingTemplateId() {
        return landingTemplateId;
    }

    public void setLandingTemplateId(UUID landingTemplateId) {
        this.landingTemplateId = landingTemplateId;
    }

    public UUID getBrandId() {
        return brandId;
    }

    public void setBrandId(UUID brandId) {
        this.brandId = brandId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
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

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
