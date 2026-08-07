package com.influencer.dao.workflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One landing page stage change, with its origin and an idempotency key (roadmap §4 rule 4).
 *
 * Append-only. The unique key on idempotency_key is what turns a duplicated or retried event
 * into a no-op rather than a second card move, and the log is what the reconciliation job and
 * any "why did this card move?" question read.
 */
@Entity
@Table(name = "stage_transitions")
public class StageTransition {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "landing_template_id", nullable = false)
    private UUID landingTemplateId;

    @Column(name = "from_stage")
    private String fromStage;

    @Column(name = "to_stage", nullable = false)
    private String toStage;

    /** board | builder | api | reconciliation. */
    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "card_id")
    private UUID cardId;

    @Column(name = "applied", nullable = false)
    private Boolean applied;

    @Column(name = "note")
    private String note;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (applied == null) {
            applied = Boolean.FALSE;
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }
    public UUID getLandingTemplateId() { return landingTemplateId; }
    public void setLandingTemplateId(UUID landingTemplateId) { this.landingTemplateId = landingTemplateId; }
    public String getFromStage() { return fromStage; }
    public void setFromStage(String fromStage) { this.fromStage = fromStage; }
    public String getToStage() { return toStage; }
    public void setToStage(String toStage) { this.toStage = toStage; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getCardId() { return cardId; }
    public void setCardId(UUID cardId) { this.cardId = cardId; }
    public Boolean getApplied() { return applied; }
    public void setApplied(Boolean applied) { this.applied = applied; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
