package com.influencer.dao.creator.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A health alert on an approved creator (roadmap C3.4).
 *
 * Statuses are open, acknowledged, snoozed and acted. There is deliberately NO status that
 * revokes a creator or changes their vetting: the alert informs a decision, a human takes it
 * (roadmap #13). A creator mid-campaign has delivered work, may be owed money, and may have
 * declined other offers to take this one — silently cutting their access because a number moved
 * would be both a commercial and a contractual mistake.
 *
 * A partial unique index keeps one OPEN alert of each type per creator, so a weekly refresh
 * does not re-raise the same warning until someone acts. Alert fatigue is the failure mode this
 * phase has to design against: an alert nobody reads is worse than no alert, because it looks
 * like coverage.
 */
@Entity
@Table(name = "creator_health_alerts")
public class CreatorHealthAlert {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    /** follower_drop | engagement_drop | inactive | new_risk_flag */
    @Column(name = "alert_type", nullable = false, updatable = false)
    private String alertType;

    @Column(name = "summary", nullable = false)
    private String summary;

    @Column(name = "previous_value")
    private BigDecimal previousValue;

    @Column(name = "current_value")
    private BigDecimal currentValue;

    @Column(name = "change_pct")
    private BigDecimal changePct;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    /** Recorded on resolution, so "we saw it and kept them" is on the record too. */
    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "open";
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }
    public UUID getCreatorId() { return creatorId; }
    public void setCreatorId(UUID creatorId) { this.creatorId = creatorId; }
    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public BigDecimal getPreviousValue() { return previousValue; }
    public void setPreviousValue(BigDecimal previousValue) { this.previousValue = previousValue; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public BigDecimal getChangePct() { return changePct; }
    public void setChangePct(BigDecimal changePct) { this.changePct = changePct; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getSnoozedUntil() { return snoozedUntil; }
    public void setSnoozedUntil(Instant snoozedUntil) { this.snoozedUntil = snoozedUntil; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
    public UUID getResolvedByUserId() { return resolvedByUserId; }
    public void setResolvedByUserId(UUID resolvedByUserId) { this.resolvedByUserId = resolvedByUserId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
