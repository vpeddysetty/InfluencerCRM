package com.influencer.dao.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * An external identity provider's assertion that it recognises this user.
 *
 * <p>Mirrors the Identity service mapping of the same name. The table was created by the phase-6
 * migration but nothing wrote to it: social signup matched users on email alone and discarded the
 * provider's subject id. That left no way to answer "which user is Facebook user 1234567890?" —
 * which is exactly the question Meta's data-deletion callback asks.
 *
 * <p>Stores the provider's subject id, never an access token. A creator's connected social account —
 * the Instagram grant used to sync follower counts — belongs to the Creator context and is a
 * separate credential with a separate lifecycle. Keeping the two apart is what stops a creator who
 * revokes our Instagram data access from also losing the ability to log in.
 */
@Entity
@Table(
        name = "federated_identities",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_federated_identity_provider_subject",
                columnNames = {"provider", "subject"}))
public class FederatedIdentity {

    /** Providers whose assertions are recognised. SAML providers are {@code saml:{accountId}}. */
    public static final String PROVIDER_GOOGLE = "google";
    public static final String PROVIDER_FACEBOOK = "facebook";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false)
    private String provider;

    /**
     * The provider's opaque, stable subject id (OIDC {@code sub}, Facebook {@code id}).
     *
     * <p>Deliberately not the email. Emails are reassigned when someone leaves an agency, and
     * matching on them is the standard account-takeover route into a federated system.
     */
    @Column(name = "subject", nullable = false)
    private String subject;

    /** The email as asserted at link time — for display and audit, never for matching. */
    @Column(name = "asserted_email")
    private String assertedEmail;

    @Column(name = "email_verified_by_idp", nullable = false)
    private boolean emailVerifiedByIdp;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "last_authenticated_at")
    private Instant lastAuthenticatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getAssertedEmail() {
        return assertedEmail;
    }

    public void setAssertedEmail(String assertedEmail) {
        this.assertedEmail = assertedEmail;
    }

    public boolean isEmailVerifiedByIdp() {
        return emailVerifiedByIdp;
    }

    public void setEmailVerifiedByIdp(boolean emailVerifiedByIdp) {
        this.emailVerifiedByIdp = emailVerifiedByIdp;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public void setLinkedAt(Instant linkedAt) {
        this.linkedAt = linkedAt;
    }

    public Instant getLastAuthenticatedAt() {
        return lastAuthenticatedAt;
    }

    public void setLastAuthenticatedAt(Instant lastAuthenticatedAt) {
        this.lastAuthenticatedAt = lastAuthenticatedAt;
    }
}
