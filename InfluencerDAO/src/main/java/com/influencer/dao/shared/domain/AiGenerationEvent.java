package com.influencer.dao.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

    // @GeneratedValue, and the reason is Hibernate rather than Postgres. With a bare @Id,
    // `persist()` on a new entity throws IdentifierGenerationException -- "must be manually
    // assigned" -- before any SQL is attempted at all. The column's `default gen_random_uuid()`
    // never gets a chance to apply, because there is no INSERT.
    //
    // The other entities here get away with a bare @Id because they reach the database through
    // `save()` on rows whose ids are already set. This one is created fresh inside the controller,
    // so it needs the generator.
    //
    // How it presented: the DAO answered 500, the BFF turned it into 502, and
    // AiGenerationAllowance.record() swallowed it by design -- so the ceiling was deployed and
    // counting nothing, which is indistinguishable from a working one until a bill arrives. The
    // warning it logs is what made it findable.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
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
