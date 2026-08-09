package com.influencer.campaign.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Drains the outbox and hands each event to the registered handlers.
 *
 * <p>Relaying in a separate transaction from the emitting write is the point: the write already
 * committed, so delivery can be retried without re-running business logic. That is the trade the
 * outbox pattern buys — at-least-once delivery, so handlers must be idempotent.
 *
 * <p>Today the "transport" is in-process handler beans. In a genuinely distributed deployment this
 * class is the only thing that changes: it publishes to a broker instead of calling handlers, and no
 * emitting context is touched.
 *
 * <p>Disabled by default. A relay that starts polling the moment someone runs the app — including
 * during tests — is a surprise; it is switched on deliberately via
 * {@code influencrm.events.relay.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "influencrm.events.relay.enabled", havingValue = "true")
public class DomainEventRelay {

    private static final Logger log = LoggerFactory.getLogger(DomainEventRelay.class);

    /** Beyond this many failures an event stops being retried and is parked as failed. */
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 50;

    private final DomainEventRepository repository;
    private final ObjectProvider<DomainEventHandler> handlers;

    public DomainEventRelay(DomainEventRepository repository, ObjectProvider<DomainEventHandler> handlers) {
        this.repository = repository;
        this.handlers = handlers;
    }

    @Scheduled(fixedDelayString = "${influencrm.events.relay.interval-ms:5000}")
    @Transactional
    public void relayPendingEvents() {
        List<DomainEvent> batch = repository.lockPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (DomainEvent event : batch) {
            try {
                dispatch(event);
                event.setStatus(DomainEvent.STATUS_PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception exception) {
                event.setAttempts(event.getAttempts() + 1);
                event.setLastError(truncate(exception.getMessage()));

                if (event.getAttempts() >= MAX_ATTEMPTS) {
                    // Park it rather than retry forever: an event failing five times is a bug to
                    // investigate, and an endless retry loop would bury the rest of the queue.
                    event.setStatus(DomainEvent.STATUS_FAILED);
                    log.error("Event {} ({}) parked as failed after {} attempts",
                            event.getId(), event.getEventType(), event.getAttempts(), exception);
                } else {
                    log.warn("Event {} ({}) failed on attempt {}; will retry",
                            event.getId(), event.getEventType(), event.getAttempts(), exception);
                }
            }
            repository.save(event);
        }
    }

    /**
     * Hands the event to every handler that claims it.
     *
     * <p>A handler that throws fails the whole event so it is retried as a unit. Handlers are
     * therefore required to be idempotent — the same event will be redelivered.
     */
    private void dispatch(DomainEvent event) {
        handlers.orderedStream()
                .filter(handler -> handler.handles(event.getEventType()))
                .forEach(handler -> handler.handle(event));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
