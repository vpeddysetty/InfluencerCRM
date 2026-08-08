package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Applies subscription changes a payment provider reports (roadmap M2.2).
 *
 * <p><b>Replay safety is the requirement, not a nicety.</b> The roadmap states it directly:
 * "Subscription state must survive a webhook replay." Providers deliver at-least-once and retry on
 * any non-2xx, so the same event <em>will</em> arrive more than once — after a timeout, a deploy,
 * or a 500 anywhere downstream. Every duplicate is recognised by provider event id and skipped
 * before anything is written.
 *
 * <p><b>Recorded before acting.</b> The event row is inserted first; the unique index on
 * {@code (provider, event_id)} makes a concurrent duplicate fail at the database rather than
 * racing through a check-then-act gap. That ordering is why two simultaneous deliveries cannot
 * both apply.
 *
 * <p><b>Nothing here trusts the payload's account id blindly.</b> The subscription is found by the
 * provider reference we stored when we created it, so an event naming an account we never
 * subscribed cannot move that account onto a paid plan.
 */
@Service
public class BillingWebhookService {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookService.class);

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public BillingWebhookService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    /** What happened, so the caller can answer 200 and say something useful in the log. */
    public record Outcome(boolean applied, String detail) {
    }

    /**
     * Handles one event.
     *
     * <p>Always returns rather than throwing on an unknown type: a provider retries anything that
     * is not 2xx, so throwing on an event we do not care about would make it retry forever.
     */
    public Outcome handle(String provider, String eventId, String eventType, JsonNode payload) {
        if (eventId == null || eventId.isBlank()) {
            // Without an id there is no way to recognise a replay, so this cannot be applied
            // safely at all.
            return new Outcome(false, "Event carried no id; ignored.");
        }

        if (alreadySeen(provider, eventId)) {
            // The whole point. Not an error — a duplicate is expected traffic.
            log.debug("[billing:{}] event {} already processed, skipping", provider, eventId);
            return new Outcome(false, "Already processed.");
        }

        if (!record(provider, eventId, eventType, payload)) {
            // The insert lost a race with a concurrent delivery of the same event. The other one
            // is applying it; this one must not.
            return new Outcome(false, "Already being processed.");
        }

        try {
            return apply(provider, eventType, payload);
        } catch (RuntimeException e) {
            // Logged, not rethrown. The event is recorded, so a provider retry would be skipped as
            // a duplicate — meaning a throw here would lose the event silently AND tell the
            // provider to try again. Better to answer 200 and leave a loud line to investigate.
            log.error("[billing:{}] event {} ({}) was recorded but could not be applied: {}",
                    provider, eventId, eventType, e.toString());
            return new Outcome(false, "Recorded but not applied — see logs.");
        }
    }

    // ---- event types -----------------------------------------------------

    private Outcome apply(String provider, String eventType, JsonNode payload) {
        String type = eventType == null ? "" : eventType.trim().toLowerCase();
        return switch (type) {
            // The user finished hosted checkout. This is where a hosted-checkout subscription
            // becomes active — not when they clicked subscribe.
            case "checkout.session.completed" -> activate(provider, payload);
            case "customer.subscription.updated" -> updateStatus(provider, payload);
            case "customer.subscription.deleted" -> cancel(provider, payload);
            case "invoice.paid", "invoice.payment_failed" -> recordInvoice(provider, type, payload);
            default -> new Outcome(false, "Unhandled event type: " + type);
        };
    }

    private Outcome activate(String provider, JsonNode payload) {
        JsonNode subscription = findByRef(provider, providerRef(payload));
        if (subscription == null) {
            return new Outcome(false, "No subscription matches that reference.");
        }
        Instant now = Instant.now();
        ObjectNode update = subscription.deepCopy();
        update.put("status", SubscriptionState.ACTIVE);
        update.put("currentPeriodStart", text(payload, "currentPeriodStart", now.toString()));
        update.put("currentPeriodEnd",
                text(payload, "currentPeriodEnd", now.plusSeconds(30L * 86400).toString()));
        return save(subscription, update, "Subscription activated.");
    }

    private Outcome updateStatus(String provider, JsonNode payload) {
        JsonNode subscription = findByRef(provider, providerRef(payload));
        if (subscription == null) {
            return new Outcome(false, "No subscription matches that reference.");
        }
        String from = subscription.path("status").asText("");
        String to = payload.path("status").asText("");
        if (!SubscriptionState.canTransition(from, to)) {
            // Refused rather than forced. An out-of-order delivery — which at-least-once makes
            // possible — must not drag a cancelled subscription back to active.
            log.warn("[billing:{}] refused status move {} -> {} for subscription {}",
                    provider, from, to, subscription.path("id").asText(""));
            return new Outcome(false, "Illegal status transition " + from + " -> " + to);
        }
        ObjectNode update = subscription.deepCopy();
        update.put("status", to);
        if (payload.hasNonNull("currentPeriodEnd")) {
            update.put("currentPeriodEnd", payload.get("currentPeriodEnd").asText());
        }
        if (payload.hasNonNull("cancelAtPeriodEnd")) {
            update.put("cancelAtPeriodEnd", payload.get("cancelAtPeriodEnd").asBoolean());
        }
        return save(subscription, update, "Status updated to " + to + ".");
    }

    private Outcome cancel(String provider, JsonNode payload) {
        JsonNode subscription = findByRef(provider, providerRef(payload));
        if (subscription == null) {
            return new Outcome(false, "No subscription matches that reference.");
        }
        ObjectNode update = subscription.deepCopy();
        update.put("status", SubscriptionState.CANCELLED);
        update.put("cancelledAt", Instant.now().toString());
        update.put("cancelAtPeriodEnd", false);
        return save(subscription, update, "Subscription cancelled.");
    }

    /**
     * Records an invoice, or updates the one already stored for this provider reference.
     *
     * <p>The lookup by provider ref is the second half of replay safety: even if the event guard
     * were bypassed, the same invoice cannot be stored twice.
     */
    private Outcome recordInvoice(String provider, String type, JsonNode payload) {
        String ref = providerRef(payload);
        boolean paid = "invoice.paid".equals(type);

        JsonNode existing = null;
        if (ref != null) {
            JsonNode rows = dao.get("/billing/invoices", Map.of("provider", provider, "providerRef", ref));
            if (rows != null && rows.isArray() && rows.size() > 0) {
                existing = rows.get(0);
            }
        }

        if (existing != null) {
            ObjectNode update = existing.deepCopy();
            update.put("status", paid ? "paid" : "open");
            if (paid) {
                update.put("paidAt", Instant.now().toString());
            }
            dao.put("/billing/invoices/" + existing.path("id").asText(), update);
            return new Outcome(true, "Invoice updated.");
        }

        ObjectNode invoice = shape.objectMapper().createObjectNode();
        invoice.put("accountId", payload.path("accountId").asText(null));
        invoice.put("subscriptionId", payload.path("subscriptionId").asText(null));
        invoice.put("amountCents", payload.path("amountCents").asLong(0));
        invoice.put("currency", payload.path("currency").asText("USD"));
        invoice.put("status", paid ? "paid" : "open");
        invoice.put("provider", provider);
        invoice.put("providerRef", ref);
        invoice.put("issuedAt", Instant.now().toString());
        if (paid) {
            invoice.put("paidAt", Instant.now().toString());
        }
        dao.post("/billing/invoices", invoice);
        return new Outcome(true, "Invoice recorded.");
    }

    // ---- internals -------------------------------------------------------

    /**
     * Saves and keeps {@code accounts.plan} in step.
     *
     * <p>The same invariant {@code SubscriptionService} holds: what is enforced must follow what
     * is billed. A webhook that changed status without this would leave a cancelled account still
     * enjoying paid limits.
     */
    private Outcome save(JsonNode subscription, ObjectNode update, String detail) {
        JsonNode saved = dao.put("/billing/subscriptions/" + subscription.path("id").asText(), update);
        String accountId = saved.path("accountId").asText(null);
        if (accountId != null) {
            String effective = SubscriptionState.effectivePlan(
                    saved.path("plan").asText(null), saved.path("status").asText(null));
            try {
                dao.patch("/tenancy/accounts/" + UUID.fromString(accountId),
                        shape.objectMapper().createObjectNode().put("plan", effective));
            } catch (RuntimeException e) {
                log.error("Webhook moved subscription {} to '{}' but accounts.plan could not be set "
                                + "to '{}': {}", saved.path("id").asText(""),
                        saved.path("status").asText(""), effective, e.toString());
            }
        }
        return new Outcome(true, detail);
    }

    private boolean alreadySeen(String provider, String eventId) {
        try {
            JsonNode rows = dao.get("/billing/events",
                    Map.of("provider", provider, "eventId", eventId));
            return rows != null && rows.isArray() && rows.size() > 0;
        } catch (RuntimeException e) {
            // Fail closed: if we cannot tell whether this is a replay, do not apply it. Applying a
            // duplicate charge or status change is worse than delaying a real one, which the
            // provider will retry anyway.
            log.warn("Could not check whether billing event {} was already seen: {}", eventId, e.toString());
            return true;
        }
    }

    private boolean record(String provider, String eventId, String eventType, JsonNode payload) {
        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("provider", provider);
        body.put("eventId", eventId);
        body.put("eventType", eventType == null ? "unknown" : eventType);
        // jsonb is mapped as String on the entity, so an object here fails the write — the same
        // guard BrandDomainService applies to the landing template's jsonb columns.
        try {
            body.put("payload", payload == null ? null : shape.objectMapper().writeValueAsString(payload));
        } catch (Exception ignored) {
            // The payload is kept for forensics only; failing to serialise it must not stop the
            // event being recorded, because the record is what prevents a replay.
            body.putNull("payload");
        }
        body.put("processedAt", Instant.now().toString());
        try {
            dao.post("/billing/events", body);
            return true;
        } catch (RuntimeException e) {
            // Most likely the unique index rejecting a concurrent duplicate, which is the guard
            // working rather than a fault.
            log.debug("Billing event {} could not be recorded (likely a duplicate): {}", eventId, e.toString());
            return false;
        }
    }

    private JsonNode findByRef(String provider, String providerRef) {
        if (providerRef == null || providerRef.isBlank()) {
            return null;
        }
        JsonNode rows = dao.get("/billing/subscriptions",
                Map.of("provider", provider, "providerRef", providerRef));
        return rows != null && rows.isArray() && rows.size() > 0 ? rows.get(0) : null;
    }

    private static String providerRef(JsonNode payload) {
        return payload == null ? null : payload.path("providerRef").asText(null);
    }

    private static String text(JsonNode payload, String field, String fallback) {
        String value = payload == null ? null : payload.path(field).asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }
}
