package com.influencer.content.shared.events;

/**
 * Reacts to a domain event emitted by another context.
 *
 * <p>Implementations live in the <em>consuming</em> context and are discovered by the relay, so an
 * emitting context never knows who listens. That is what lets the attribution → commission → payout
 * chain stop being a chain of direct calls.
 *
 * <p><strong>Handlers must be idempotent.</strong> The outbox gives at-least-once delivery: a relay
 * that crashes after handling but before marking the row published will redeliver the same event.
 */
public interface DomainEventHandler {

    /** Whether this handler wants the given event type, e.g. {@code "CommissionApproved"}. */
    boolean handles(String eventType);

    /**
     * Processes the event. Throwing marks the event for retry; after repeated failures the relay
     * parks it as {@code failed} rather than retrying forever.
     */
    void handle(DomainEvent event);
}
