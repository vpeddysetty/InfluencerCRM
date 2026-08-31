package com.influencer.dao.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One billed AI call (V48).
 *
 * <p>In {@code shared} rather than in a context because no single context owns it: page generation
 * lives in content, the allowance is a plan question in identity, and the row is written by
 * whichever endpoint made the call. A table two contexts both write to belongs to neither.
 */
@Entity
@Table(name = "ai_generation_events")
public class AiGenerationEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Plans are billed per account, so the allowance is counted per account. */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Which brand asked, when one did. Recorded for diagnosis, never for counting. */
    @Column(name = "brand_id")
    private UUID brandId;

    @Column(name = "kind", nullable = false)
    private String kind;

    /** {@code anthropic} or {@code template}. Only the former counts against the allowance. */
    @Column(name = "generator", nullable = false)
    private String generator = "anthropic";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }

    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getGenerator() { return generator; }
    public void setGenerator(String generator) { this.generator = generator; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
