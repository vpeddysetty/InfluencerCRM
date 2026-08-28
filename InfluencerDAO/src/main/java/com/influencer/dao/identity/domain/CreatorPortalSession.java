package com.influencer.dao.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A creator's portal session (roadmap PR-40, {@code V45}).
 *
 * <p><b>Why this is a table and not a map.</b> These lived in a {@code ConcurrentHashMap} in the
 * BFF, which the code called out honestly as infrastructure-ahead-of-need while the portal had no
 * real users. It stops being acceptable now, and the reason is not multi-instance: an ASG instance
 * refresh is the <i>live step of every deploy</i> in this project, so an in-memory store signs out
 * every creator on every release. A creator halfway through editing a page would lose their
 * session to a deploy they cannot see coming.
 *
 * <p><b>Only the SHA-256 hash of the token is stored</b> — same reasoning as {@link RefreshToken}
 * and {@code password_hash}. The token is a bearer credential, so a dump of this table, a backup,
 * or an errant query must not yield working sessions.
 *
 * <p>SHA-256 and not BCrypt, deliberately: the token is 256 bits of {@code SecureRandom}, so there
 * is no low-entropy secret to brute-force and nothing for a slow hash to protect. A per-request
 * BCrypt verification would cost ~100ms on every single authenticated call for no security gain.
 *
 * <p>The primary key IS the hash. A session has no identity apart from its token, and a surrogate
 * id would add a column nothing queries by.
 */
@Entity
@Table(name = "creator_portal_sessions", schema = "identity")
public class CreatorPortalSession {

    @Id
    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "creator_identity_id", nullable = false)
    private UUID creatorIdentityId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Set when the session is explicitly ended, rather than deleting the row.
     *
     * <p>Keeps "was this session revoked, or did it simply expire?" answerable during an incident
     * — a distinction that matters exactly when someone is asking whether an account was misused.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public UUID getCreatorIdentityId() {
        return creatorIdentityId;
    }

    public void setCreatorIdentityId(UUID creatorIdentityId) {
        this.creatorIdentityId = creatorIdentityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }
}
