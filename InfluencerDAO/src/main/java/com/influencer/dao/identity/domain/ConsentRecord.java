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
 * One person's acceptance of one legal document, at one moment.
 *
 * <p>Evidence for GDPR Article 7(1), which requires the controller to be able to demonstrate that a
 * data subject consented — not merely that a form said they had. So the row records who, when, which
 * document version, and from which surface.
 *
 * <p><b>Append-only.</b> Modelled on {@link BillingEvent}: there is no update path and deliberately
 * no {@code updatedAt}. A withdrawal or a re-acceptance is a NEW row with a new {@code granted}
 * value; the current state is the latest row per subject and consent type. An audit trail that can
 * be edited is not an audit trail.
 *
 * <p><b>Why the subject is a type/id pair and not a foreign key.</b> The four capture surfaces do
 * not share a parent table — brand owners, agency owners and invited teammates are {@code users},
 * creator-portal accounts are {@code creator_identities}, and a landing-page lead has no account at
 * all. A consent record must also outlive the account it describes: if a user is deleted, this row
 * is what shows the deletion was lawful, so a cascading FK would erase the evidence exactly when it
 * matters.
 */
@Entity
@Table(name = "consent_records", schema = "identity")
public class ConsentRecord {

    public static final String SUBJECT_USER = "user";
    public static final String SUBJECT_CREATOR_IDENTITY = "creator_identity";
    public static final String SUBJECT_LEAD = "lead";

    public static final String TYPE_TERMS = "terms_of_service";
    public static final String TYPE_PRIVACY = "privacy_policy";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subject_type", nullable = false, updatable = false)
    private String subjectType;

    /** Null only for a lead, which has no account row to point at. */
    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    @Column(name = "subject_email", nullable = false, updatable = false)
    private String subjectEmail;

    @Column(name = "consent_type", nullable = false, updatable = false)
    private String consentType;

    @Column(name = "document_version", nullable = false, updatable = false)
    private String documentVersion;

    @Column(name = "granted", nullable = false, updatable = false)
    private boolean granted = true;

    /** The capture surface, e.g. {@code brand_signup} — how "what were they shown" is reconstructed. */
    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    /**
     * Stored as text and cast by Postgres, not as an {@code inet}-typed field.
     *
     * <p>Hibernate has no built-in {@code inet} mapping, and the DB column is {@code inet}, so the
     * write is an explicit cast. Same approach the codebase already uses for {@code user_role}.
     */
    @Column(name = "ip_address", columnDefinition = "inet", updatable = false)
    @org.hibernate.annotations.ColumnTransformer(write = "?::inet")
    private String ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    /**
     * Surface-specific context, as text.
     *
     * <p>String rather than a JSON type for the same reason {@link BillingEvent#getPayload()} is:
     * the gateway sends it as a string and a type mismatch fails the write.
     */
    @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (metadata == null || metadata.isBlank()) {
            metadata = "{}";
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(UUID subjectId) {
        this.subjectId = subjectId;
    }

    public String getSubjectEmail() {
        return subjectEmail;
    }

    public void setSubjectEmail(String subjectEmail) {
        this.subjectEmail = subjectEmail;
    }

    public String getConsentType() {
        return consentType;
    }

    public void setConsentType(String consentType) {
        this.consentType = consentType;
    }

    public String getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(String documentVersion) {
        this.documentVersion = documentVersion;
    }

    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
