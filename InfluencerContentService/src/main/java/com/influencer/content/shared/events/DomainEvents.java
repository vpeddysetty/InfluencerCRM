package com.influencer.content.shared.events;

import java.util.Map;
import java.util.UUID;

/**
 * The contract contexts use to announce that something happened.
 *
 * <p>An interface rather than a concrete class so that a context depends on the <em>act</em> of
 * publishing, not on the outbox being the mechanism. Phase 5 can swap the implementation for a
 * broker adapter, and tests can substitute a recording double, without either touching a caller.
 */
public interface DomainEvents {

    /**
     * Records an event in the caller's transaction.
     *
     * @param context       the emitting bounded context, e.g. {@code "finance"}
     * @param aggregateType the aggregate the event is about, e.g. {@code "InfluencerCommission"}
     * @param eventType     past tense, e.g. {@code "CommissionAccrued"} — events describe what
     *                      happened, never what should happen next
     * @return the recorded event's id
     */
    UUID publish(String context,
                 String aggregateType,
                 UUID aggregateId,
                 UUID brandId,
                 String eventType,
                 Map<String, Object> payload);
}
