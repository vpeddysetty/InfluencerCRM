package com.influencer.content.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Grants one creator identity access to one brand-owned landing page (roadmap G.1).
 *
 * Two things this deliberately does not model:
 *
 * There is no ownership column. Every landing page is owned by a brand (decision #1); this row
 * grants access to a page that already belongs to someone, and a creator can never author a
 * page of their own.
 *
 * There is no publish right. `rights` is comment or edit, at the database as well as here. A
 * collaborator may shape a page; releasing it to a domain or a social account requires
 * content:publish, which only account members hold.
 *
 * Revocation sets revoked_at rather than deleting, so the record of who had access and when
 * survives the access itself.
 */
@Entity
@Table(name = "landing_page_collaborators")
public class LandingPageCollaborator {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "landing_template_id", nullable = false, updatable = false)
    private UUID landingTemplateId;

    @Column(name = "brand_id", nullable = false, updatable = false)
    private UUID brandId;

    /** The creator's portal identity — the person, not one of their per-brand creator rows. */
    @Column(name = "creator_identity_id", nullable = false, updatable = false)
    private UUID creatorIdentityId;

    @Column(name = "rights", nullable = false)
    private String rights;

    @Column(name = "granted_by_user_id")
    private UUID grantedByUserId;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (grantedAt == null) grantedAt = Instant.now();
        if (rights == null || rights.isBlank()) rights = "edit";
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getLandingTemplateId() { return landingTemplateId; }
    public void setLandingTemplateId(UUID landingTemplateId) { this.landingTemplateId = landingTemplateId; }
    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }
    public UUID getCreatorIdentityId() { return creatorIdentityId; }
    public void setCreatorIdentityId(UUID creatorIdentityId) { this.creatorIdentityId = creatorIdentityId; }
    public String getRights() { return rights; }
    public void setRights(String rights) { this.rights = rights; }
    public UUID getGrantedByUserId() { return grantedByUserId; }
    public void setGrantedByUserId(UUID grantedByUserId) { this.grantedByUserId = grantedByUserId; }
    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant grantedAt) { this.grantedAt = grantedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public UUID getRevokedByUserId() { return revokedByUserId; }
    public void setRevokedByUserId(UUID revokedByUserId) { this.revokedByUserId = revokedByUserId; }
}
