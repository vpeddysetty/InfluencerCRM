package com.influencer.dao.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An offer to join an account with a given role.
 *
 * <p>Separate from {@link Membership} because the invitee may have no user record yet — there is
 * nothing to hang a membership on until they accept. It also gives the inviter something to revoke
 * before it is used.
 *
 * <p>Only {@code tokenHash} is stored. The token itself is returned once, at creation, and never
 * persisted: it grants access to an account, so a database dump must not contain usable
 * invitations.
 */
@Entity
@Table(name = "member_invitations")
public class MemberInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "email", nullable = false)
    private String email;

    /** Stored as the Postgres {@code account_role} enum; written through a native query cast. */
    @Column(name = "role", nullable = false, columnDefinition = "account_role")
    private String role;

    /** Null means account-wide access; set means this brand only. */
    @Column(name = "brand_id")
    private UUID brandId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "status", nullable = false)
    private String status = "pending";

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public UUID getBrandId() {
        return brandId;
    }

    public void setBrandId(UUID brandId) {
        this.brandId = brandId;
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

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(UUID invitedBy) {
        this.invitedBy = invitedBy;
    }

    public UUID getAcceptedBy() {
        return acceptedBy;
    }

    public void setAcceptedBy(UUID acceptedBy) {
        this.acceptedBy = acceptedBy;
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
