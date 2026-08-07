package com.influencer.creator.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * A brand disputing a creator's audience quality (roadmap C2.8).
 *
 * signalSnapshot records what our OWN signal said at the time of the complaint. That is what
 * turns each dispute into a labelled example of the signal being wrong — the only ground truth
 * available for tuning in-house thresholds, and the trigger for engaging a vendor.
 */
@Entity
@Table(name = "creator_quality_reports")
public class CreatorQualityReport {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "reported_by_user_id")
    private UUID reportedByUserId;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "detail")
    private String detail;

    @Column(name = "signal_snapshot", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String signalSnapshot;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "open";
        if (category == null) category = "other";
        if (signalSnapshot == null) signalSnapshot = "{}";
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }
    public UUID getCreatorId() { return creatorId; }
    public void setCreatorId(UUID creatorId) { this.creatorId = creatorId; }
    public UUID getReportedByUserId() { return reportedByUserId; }
    public void setReportedByUserId(UUID reportedByUserId) { this.reportedByUserId = reportedByUserId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getSignalSnapshot() { return signalSnapshot; }
    public void setSignalSnapshot(String signalSnapshot) { this.signalSnapshot = signalSnapshot; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
