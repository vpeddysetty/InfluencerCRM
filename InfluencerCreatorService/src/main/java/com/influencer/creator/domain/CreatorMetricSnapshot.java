package com.influencer.creator.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One metric reading for a creator at a moment in time (roadmap C3.2).
 *
 * Append-only: there is no update path and no setter is called after persistence. A snapshot
 * records what was true when it was taken, and rewriting it destroys the trend it exists to
 * support — without history there is no way to tell a slide from a correction, and no evidence
 * when a brand asks why an alert fired.
 */
@Entity
@Table(name = "creator_metric_snapshots")
public class CreatorMetricSnapshot {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "follower_count")
    private Long followerCount;

    @Column(name = "engagement_rate")
    private BigDecimal engagementRate;

    @Column(name = "average_views")
    private Long averageViews;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /** Provenance travels with the numbers: a trend mixing measured and simulated points is not a trend. */
    @Column(name = "metrics_source")
    private String metricsSource;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (capturedAt == null) capturedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }
    public UUID getCreatorId() { return creatorId; }
    public void setCreatorId(UUID creatorId) { this.creatorId = creatorId; }
    public Long getFollowerCount() { return followerCount; }
    public void setFollowerCount(Long followerCount) { this.followerCount = followerCount; }
    public BigDecimal getEngagementRate() { return engagementRate; }
    public void setEngagementRate(BigDecimal engagementRate) { this.engagementRate = engagementRate; }
    public Long getAverageViews() { return averageViews; }
    public void setAverageViews(Long averageViews) { this.averageViews = averageViews; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public String getMetricsSource() { return metricsSource; }
    public void setMetricsSource(String metricsSource) { this.metricsSource = metricsSource; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
}
