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
 * One pass of a landing page between a brand and a creator (roadmap PR-40, {@code V45}).
 *
 * <p><b>Separate from {@code landing_page_transitions}, which records STAGE changes.</b> A handoff
 * is not a stage change: the commonest one — a creator sending work back — moves the turn and
 * leaves the stage exactly where it was. Recording them together would mean rows whose from-stage
 * and to-stage are equal, which every existing reader of that table would have to learn to ignore.
 *
 * <p>Exactly one actor is set, enforced by a check constraint rather than by convention: a creator
 * has no user row and a brand user has no creator identity, so a row carrying both would be
 * describing something that cannot happen.
 */
@Entity
@Table(name = "page_handoffs", schema = "content")
public class PageHandoff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "landing_template_id", nullable = false)
    private UUID landingTemplateId;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "to_turn", nullable = false)
    private String toTurn;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_creator_identity_id")
    private UUID actorCreatorIdentityId;

    /** What the brand asked for, or what the creator said on returning it. */
    @Column(name = "note")
    private String note;

    /**
     * Per-occurrence, never {@code templateId:from->to}.
     *
     * <p>V24's transition log learned this the expensive way: a key derived from the endpoints
     * collides when work legitimately goes round the loop twice, and the second pass vanishes from
     * the audit trail while the page itself still moves — so the record is wrong exactly when
     * somebody is trying to reconstruct what happened.
     */
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
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

    public String getToTurn() {
        return toTurn;
    }

    public void setToTurn(String toTurn) {
        this.toTurn = toTurn;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(UUID actorUserId) {
        this.actorUserId = actorUserId;
    }

    public UUID getActorCreatorIdentityId() {
        return actorCreatorIdentityId;
    }

    public void setActorCreatorIdentityId(UUID actorCreatorIdentityId) {
        this.actorCreatorIdentityId = actorCreatorIdentityId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
