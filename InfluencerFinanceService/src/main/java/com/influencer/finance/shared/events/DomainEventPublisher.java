package com.influencer.finance.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes domain events into the outbox.
 *
 * <p>The write joins the caller's transaction ({@code Propagation.MANDATORY}) on purpose: an event
 * must commit or roll back with the state change that produced it. Publishing in a separate
 * transaction would allow "the row changed but no event was recorded", or the reverse — both of
 * which are far harder to detect than a failed insert.
 *
 * <p>Contexts call this instead of calling each other. Today a relay can process the table
 * in-process; in Phase 5 the relay becomes a broker adapter and no emitting context changes.
 */
@Service
public class DomainEventPublisher implements DomainEvents {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final DomainEventRepository repository;
    private final ObjectMapper objectMapper;

    public DomainEventPublisher(DomainEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an event in the caller's transaction.
     *
     * @param context       the emitting bounded context, e.g. {@code "attribution"}
     * @param aggregateType the aggregate the event is about, e.g. {@code "InfluencerCommission"}
     * @param eventType     past tense, e.g. {@code "CommissionAccrued"} — events describe what
     *                      happened, never what should happen next
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String context,
                        String aggregateType,
                        UUID aggregateId,
                        UUID brandId,
                        String eventType,
                        Map<String, Object> payload) {

        DomainEvent event = new DomainEvent();
        event.setContext(context);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setBrandId(brandId);
        event.setEventType(eventType);
        event.setPayload(writePayload(payload));
        event.setStatus(DomainEvent.STATUS_PENDING);
        event.setOccurredAt(Instant.now());

        DomainEvent saved = repository.save(event);
        log.debug("Recorded {} for {} {} (brand {})", eventType, aggregateType, aggregateId, brandId);
        return saved.getId();
    }

    private String writePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            // Failing the whole business transaction because an event payload would not serialise
            // is the right trade: a silently empty payload is worse than a loud failure.
            throw new IllegalArgumentException(
                    "Unable to serialise payload for event " + payload.keySet(), exception);
        }
    }
}
