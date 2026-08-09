package com.influencer.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.finance.shared.events.DomainEvent;
import com.influencer.finance.shared.events.DomainEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@code CommissionApproved} by recording that the commission is now payable.
 *
 * <p>This is the first working link of the event chain the plan describes
 * (SaleAttributed → CommissionAccrued → PayoutRequested → …). It is deliberately small: the value
 * being demonstrated is that a context can react to an event <em>without</em> the emitter calling
 * it, which is the property that makes extraction possible later.
 *
 * <p>Idempotent by construction — it derives no new state from previous runs, so a redelivered
 * event produces the same result. That matters because the outbox guarantees at-least-once
 * delivery, never exactly-once.
 */
@Component
public class CommissionApprovedHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CommissionApprovedHandler.class);

    private final ObjectMapper objectMapper;

    public CommissionApprovedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean handles(String eventType) {
        return "CommissionApproved".equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        String commissionId = readText(event, "commissionId");
        String amount = readText(event, "commissionAmount");

        // A payout batch is created deliberately by a FINANCE user, never automatically — the
        // separation of duties verified in Phase 3 would be meaningless if approving a commission
        // silently paid it. So this records eligibility and stops there.
        log.info("Commission {} approved for brand {} (amount {}) — now eligible for payout",
                commissionId, event.getBrandId(), amount);
    }

    private String readText(DomainEvent event, String field) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            JsonNode value = payload.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            // A malformed payload must not stall the queue behind it; the event is still
            // observable in the outbox for investigation.
            log.warn("Unreadable payload on event {}: {}", event.getId(), exception.getMessage());
            return null;
        }
    }
}
