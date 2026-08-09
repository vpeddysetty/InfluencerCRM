package com.influencer.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A managed brand — the tenancy key for every domain table from Phase 2 onward.
 *
 * <p>A solo account owns exactly one brand; an agency account owns many. That is deliberate: the
 * single-brand product is the degenerate case of the agency product, so there is one code path.
 */
@Entity
@Table(name = "brands")
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", nullable = false)
    private String status = "active";

    @Column(name = "custom_attributes", columnDefinition = "jsonb")
    private String customAttributes = "{}";

    /**
     * The users.id this brand was derived from during the Phase 1 backfill. Retained so the
     * Phase 1 bridge triggers can still map user_id -> brand_id; dropped once those triggers go.
     */
    @Column(name = "legacy_user_id")
    private UUID legacyUserId;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCustomAttributes() {
        return customAttributes;
    }

    public void setCustomAttributes(String customAttributes) {
        this.customAttributes = customAttributes;
    }

    public UUID getLegacyUserId() {
        return legacyUserId;
    }

    public void setLegacyUserId(UUID legacyUserId) {
        this.legacyUserId = legacyUserId;
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
