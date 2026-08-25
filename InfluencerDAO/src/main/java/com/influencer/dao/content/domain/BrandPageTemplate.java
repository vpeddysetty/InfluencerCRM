package com.influencer.dao.content.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * A page shape a brand saved to reuse on its next campaign (roadmap PR-39, piece D).
 *
 * <p><b>Deliberately not a row in {@code landing_templates}.</b> That table's
 * {@code uq_landing_templates_campaign} enforces one page per campaign, which V24 records as a
 * product decision the public slug and coupon-assignment logic both assume. A reusable template
 * has no campaign, so storing it there would mean relaxing that constraint for every real page in
 * order to hold something that is not a page.
 *
 * <p><b>Sections, never rendered HTML.</b> Storing the typed section list means a later change to
 * the design system reaches every saved template at once. Storing markup would freeze this
 * month's styling into every page a brand ever saved — which is exactly the failure the curated
 * editor exists to prevent, arriving by a different route.
 */
@Entity
@Table(name = "brand_page_templates")
public class BrandPageTemplate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * The stripped section list.
     *
     * <p>NOT NULL with no default: a template with no sections is not a template, and letting one
     * exist would put an entry in the picker that produces an empty page.
     */
    @Column(name = "sections", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String sections;

    /** Who saved it — shown in the picker so a shared brand knows where a template came from. */
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
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
    void onUpdate() {
        updatedAt = Instant.now();
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSections() {
        return sections;
    }

    public void setSections(String sections) {
        this.sections = sections;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
