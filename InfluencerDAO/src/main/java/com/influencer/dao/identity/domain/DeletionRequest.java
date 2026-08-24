package com.influencer.dao.identity.domain;

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
 * One request to delete personal data, and what was done about it.
 *
 * <p>The evidence that {@code /data-deletion/} was honoured. The published page promises
 * acknowledgement within five business days and completion within thirty, and without a row per
 * request the only record that either happened is a support mailbox — which is not an audit trail.
 *
 * <p><b>This outlives the user it describes.</b> {@code subjectUserId} is {@code ON DELETE SET NULL}
 * in V37, never CASCADE, for the same reason {@link ConsentRecord} has no foreign key at all: the
 * record that a deletion was requested and completed is precisely what proves the deletion was
 * lawful, so cascading it would destroy the evidence at the moment it becomes relevant.
 * {@code subjectEmail} carries the identity forward once the user row is gone.
 *
 * <h2>Nothing is deleted without approval</h2>
 *
 * <p>{@code approvedAt} is a gate, not a timestamp. Requests arrive by email, sender addresses are
 * trivially forged, and an automated purge would let anyone destroy anyone else's account by
 * sending mail that claims to be from them. V40 expresses the same rule as a CHECK constraint —
 * {@code completed_at is null or approved_at is not null} — so a bug in the service still cannot
 * complete a request nobody authorised.
 *
 * <p>Only the SHA-256 of the approval token is stored. The token authorises an irreversible
 * destruction of data, which makes it a credential, and a leaked dump must not contain working ones.
 */
@Entity
@Table(name = "deletion_requests", schema = "identity")
public class DeletionRequest {

    /** The whole account and the data it owns. */
    public static final String SCOPE_ACCOUNT = "account";

    /** Only the data obtained from one federated provider, leaving the account intact. */
    public static final String SCOPE_PROVIDER = "provider";

    public static final String SOURCE_EMAIL = "email";
    public static final String SOURCE_MANUAL = "manual";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null once the user row is purged; present while the request is in flight. */
    @Column(name = "subject_user_id")
    private UUID subjectUserId;

    /** The identity that outlives the user row. Always populated. */
    @Column(name = "subject_email", nullable = false)
    private String subjectEmail;

    @Column(name = "scope", nullable = false)
    private String scope;

    /** Set only when {@code scope = 'provider'}: {@code google} or {@code facebook}. */
    @Column(name = "provider")
    private String provider;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * A refusal is an outcome, not an error.
     *
     * <p>The published page says we must refuse what we cannot attribute, and an owner who has not
     * transferred their workspace is refused for a different reason — deleting them would erase
     * records the brand is responsible for, belonging to other people.
     */
    @Column(name = "refused_at")
    private Instant refusedAt;

    @Column(name = "refused_reason")
    private String refusedReason;

    /** What was actually removed, written by the purge. */
    @Column(name = "outcome_note")
    private String outcomeNote;

    // --- approval ---------------------------------------------------------

    @Column(name = "approval_token_hash")
    private String approvalTokenHash;

    @Column(name = "approval_expires_at")
    private Instant approvalExpiresAt;

    /** The gate. Null means no human has authorised this. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /**
     * Who authorised it, by email address.
     *
     * <p>Text rather than a foreign key: the approver is an operator who need not have an account,
     * and the record of who authorised a deletion must outlive their employment.
     */
    @Column(name = "approved_by")
    private String approvedBy;

    // --- notification -----------------------------------------------------

    @Column(name = "operator_notified_at")
    private Instant operatorNotifiedAt;

    @Column(name = "requester_notified_at")
    private Instant requesterNotifiedAt;

    // --- provenance -------------------------------------------------------

    /**
     * The raw message in the intake bucket.
     *
     * <p>That bucket expires objects after 90 days, so this key will eventually dangle. Deliberate:
     * the audit trail is this row, and the raw message is working data kept only long enough to
     * investigate a request that was mishandled.
     */
    @Column(name = "raw_message_s3_key")
    private String rawMessageS3Key;

    /** {@code email} or {@code manual} — a request entered by hand must be distinguishable. */
    @Column(name = "intake_source", nullable = false)
    private String intakeSource = SOURCE_EMAIL;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (scope == null) {
            scope = SCOPE_ACCOUNT;
        }
        if (intakeSource == null) {
            intakeSource = SOURCE_EMAIL;
        }
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

    public String getApprovalTokenHash() {
        return approvalTokenHash;
    }

    public void setApprovalTokenHash(String approvalTokenHash) {
        this.approvalTokenHash = approvalTokenHash;
    }

    public Instant getApprovalExpiresAt() {
        return approvalExpiresAt;
    }

    public void setApprovalExpiresAt(Instant approvalExpiresAt) {
        this.approvalExpiresAt = approvalExpiresAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getOperatorNotifiedAt() {
        return operatorNotifiedAt;
    }

    public void setOperatorNotifiedAt(Instant operatorNotifiedAt) {
        this.operatorNotifiedAt = operatorNotifiedAt;
    }

    public Instant getRequesterNotifiedAt() {
        return requesterNotifiedAt;
    }

    public void setRequesterNotifiedAt(Instant requesterNotifiedAt) {
        this.requesterNotifiedAt = requesterNotifiedAt;
    }

    public String getRawMessageS3Key() {
        return rawMessageS3Key;
    }

    public void setRawMessageS3Key(String rawMessageS3Key) {
        this.rawMessageS3Key = rawMessageS3Key;
    }

    public String getIntakeSource() {
        return intakeSource;
    }

    public void setIntakeSource(String intakeSource) {
        this.intakeSource = intakeSource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
