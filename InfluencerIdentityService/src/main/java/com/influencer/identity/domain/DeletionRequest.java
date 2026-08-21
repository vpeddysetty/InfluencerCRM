package com.influencer.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A data-deletion request received by email, per the process published at /data-deletion/.
 *
 * <p><b>This outlives the user it describes.</b> {@code subjectUserId} is a plain column rather
 * than a {@code @ManyToOne}, and the FK behind it is {@code ON DELETE SET NULL}. The same
 * reasoning as {@code ConsentRecord}: the record that a deletion was requested and carried out is
 * the evidence the deletion was lawful, so it must survive the purge it documents.
 * {@code subjectEmail} carries the identity forward once the user row is gone.
 */
@Entity
@Table(name = "deletion_requests", schema = "identity")
public class DeletionRequest {

    /** The whole account and the data it owns. */
    public static final String SCOPE_ACCOUNT = "account";

    /** Only the data obtained from one federated provider, leaving the account intact. */
    public static final String SCOPE_PROVIDER = "provider";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null once the subject's user row has been purged. */
    @Column(name = "subject_user_id")
    private UUID subjectUserId;

    @Column(name = "subject_email", nullable = false)
    private String subjectEmail;

    @Column(name = "scope", nullable = false)
    private String scope;

    /** Set only when {@link #scope} is {@link #SCOPE_PROVIDER}. */
    @Column(name = "provider")
    private String provider;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "refused_at")
    private Instant refusedAt;

    @Column(name = "refused_reason")
    private String refusedReason;

    @Column(name = "outcome_note")
    private String outcomeNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }

    /** Whether this request is still awaiting action. */
    public boolean isOpen() {
        return completedAt == null && refusedAt == null;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSubjectUserId() {
        return subjectUserId;
    }

    public void setSubjectUserId(UUID subjectUserId) {
        this.subjectUserId = subjectUserId;
    }

    public String getSubjectEmail() {
        return subjectEmail;
    }

    public void setSubjectEmail(String subjectEmail) {
        this.subjectEmail = subjectEmail;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getRefusedAt() {
        return refusedAt;
    }

    public void setRefusedAt(Instant refusedAt) {
        this.refusedAt = refusedAt;
    }

    public String getRefusedReason() {
        return refusedReason;
    }

    public void setRefusedReason(String refusedReason) {
        this.refusedReason = refusedReason;
    }

    public String getOutcomeNote() {
        return outcomeNote;
    }

    public void setOutcomeNote(String outcomeNote) {
        this.outcomeNote = outcomeNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
