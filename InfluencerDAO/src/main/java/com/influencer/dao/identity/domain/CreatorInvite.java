package com.influencer.dao.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation for a creator to work with a brand (roadmap PR-41, {@code V46}).
 *
 * <p><b>What this exists to break.</b> {@code PageCollaborationService.invite} refuses unless the
 * creator already holds a <i>confirmed</i> {@code creator_identity_links} row against the brand,
 * and the only route to {@code confirmed} was a creator claiming a brand they had to be told about
 * out of band. So a brand could not invite a creator until the creator was already known, and the
 * creator could not become known without the brand already inviting them. Redeeming one of these
 * creates the identity and the confirmed link together.
 *
 * <p><b>Only the SHA-256 hash of the token is stored</b> — same reasoning as {@link RefreshToken},
 * {@link CreatorPortalSession} and {@code password_hash}. The token grants collaborator access to
 * a brand's unpublished pages, so a database dump must not contain working invitations.
 */
@Entity
@Table(name = "creator_invites", schema = "identity")
public class CreatorInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "email", nullable = false)
    private String email;

    /** The creator row this is about, when the brand already has one. Null for a cold invite. */
    @Column(name = "creator_id")
    private UUID creatorId;

    /** The page the brand wants help with, if any. Null for a plain "come and work with us". */
    @Column(name = "landing_template_id")
    private UUID landingTemplateId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "invited_by_user_id")
    private UUID invitedByUserId;

    /**
     * Who actually redeemed it.
     *
     * <p>Kept after acceptance rather than discarded, so "an address was forwarded — who took
     * this?" stays answerable. That question only ever gets asked once something has gone wrong,
     * which is exactly when the row must already exist.
     */
    @Column(name = "accepted_by_creator_identity_id")
    private UUID acceptedByCreatorIdentityId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = "pending";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getBrandId() {
        return brandId;
    }

    public void setBrandId(UUID brandId) {
        this.brandId = brandId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(UUID creatorId) {
        this.creatorId = creatorId;
    }

    public UUID getLandingTemplateId() {
        return landingTemplateId;
    }

    public void setLandingTemplateId(UUID landingTemplateId) {
        this.landingTemplateId = landingTemplateId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getInvitedByUserId() {
        return invitedByUserId;
    }

    public void setInvitedByUserId(UUID invitedByUserId) {
        this.invitedByUserId = invitedByUserId;
    }

    public UUID getAcceptedByCreatorIdentityId() {
        return acceptedByCreatorIdentityId;
    }

    public void setAcceptedByCreatorIdentityId(UUID acceptedByCreatorIdentityId) {
        this.acceptedByCreatorIdentityId = acceptedByCreatorIdentityId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
