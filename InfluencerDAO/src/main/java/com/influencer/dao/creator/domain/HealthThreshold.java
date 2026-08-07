package com.influencer.dao.creator.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-brand alert thresholds (roadmap C3.3).
 *
 * Per brand rather than platform-wide, because a 20% follower drop means very different things
 * at 5k and 5M — and because platform defaults are how alert fatigue starts. An alert nobody
 * reads is worse than no alert, since it looks like coverage.
 */
@Entity
@Table(name = "health_thresholds")
public class HealthThreshold {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "follower_drop_pct", nullable = false)
    private BigDecimal followerDropPct;

    @Column(name = "engagement_drop_pct", nullable = false)
    private BigDecimal engagementDropPct;

    @Column(name = "inactive_days", nullable = false)
    private Integer inactiveDays;

    /** The window a drop is measured over. */
    @Column(name = "window_days", nullable = false)
    private Integer windowDays;

    /** A new risk flag always alerts: brand safety is not a matter of degree. */
    @Column(name = "alert_on_new_risk_flag", nullable = false)
    private Boolean alertOnNewRiskFlag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        applyDefaults();
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        applyDefaults();
        updatedAt = Instant.now();
    }

    /** The roadmap's defaults, applied when a brand has not set its own. */
    private void applyDefaults() {
        if (followerDropPct == null) followerDropPct = new BigDecimal("20.00");
        if (engagementDropPct == null) engagementDropPct = new BigDecimal("30.00");
        if (inactiveDays == null) inactiveDays = 45;
        if (windowDays == null) windowDays = 30;
        if (alertOnNewRiskFlag == null) alertOnNewRiskFlag = Boolean.TRUE;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }
    public BigDecimal getFollowerDropPct() { return followerDropPct; }
    public void setFollowerDropPct(BigDecimal followerDropPct) { this.followerDropPct = followerDropPct; }
    public BigDecimal getEngagementDropPct() { return engagementDropPct; }
    public void setEngagementDropPct(BigDecimal engagementDropPct) { this.engagementDropPct = engagementDropPct; }
    public Integer getInactiveDays() { return inactiveDays; }
    public void setInactiveDays(Integer inactiveDays) { this.inactiveDays = inactiveDays; }
    public Integer getWindowDays() { return windowDays; }
    public void setWindowDays(Integer windowDays) { this.windowDays = windowDays; }
    public Boolean getAlertOnNewRiskFlag() { return alertOnNewRiskFlag; }
    public void setAlertOnNewRiskFlag(Boolean alertOnNewRiskFlag) { this.alertOnNewRiskFlag = alertOnNewRiskFlag; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
