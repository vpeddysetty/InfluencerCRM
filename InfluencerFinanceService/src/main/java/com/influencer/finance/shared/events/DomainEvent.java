package com.influencer.finance.shared.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A row in the transactional outbox.
 *
 * <p>Lives in {@code shared} because every context emits events and no context owns the mechanism.
 * The payload is opaque JSON deliberately: a typed event class per context would have to be visible
 * to consumers, which is precisely the cross-context coupling the outbox exists to avoid.
 */
@Entity
@Table(name = "domain_events")
public class DomainEvent {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The emitting bounded context, e.g. "attribution". */
    @Column(name = "context", nullable = false)
    private String context;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private UUID aggregateId;

    /** Tenancy travels with the event so consumers never have to infer it. */
    @Column(name = "brand_id")
    private UUID brandId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload = "{}";

    @Column(name = "status", nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(UUID aggregateId) {
        this.aggregateId = aggregateId;
    }

    public UUID getBrandId() {
        return brandId;
    }

    public void setBrandId(UUID brandId) {
        this.brandId = brandId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
