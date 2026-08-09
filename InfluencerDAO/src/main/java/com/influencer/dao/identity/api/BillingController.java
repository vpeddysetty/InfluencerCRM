package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.BillingEvent;
import com.influencer.dao.identity.domain.BillingInvoice;
import com.influencer.dao.identity.domain.Subscription;
import com.influencer.dao.identity.infrastructure.BillingEventRepository;
import com.influencer.dao.identity.infrastructure.BillingInvoiceRepository;
import com.influencer.dao.identity.infrastructure.SubscriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Storage for subscriptions and invoices (roadmap M2.1/M2.2).
 *
 * <p>Deliberately thin, like every other DAO controller: no lifecycle rules live here. Which
 * transitions are legal is {@code SubscriptionState} in the BFF, and who may make them is
 * {@code RolePermissions}. A DAO that enforced its own version of either would be a second source
 * of truth to keep in step.
 */
@RestController
@RequestMapping("/billing")
public class BillingController {

    private final SubscriptionRepository subscriptions;
    private final BillingInvoiceRepository invoices;
    private final BillingEventRepository events;

    public BillingController(SubscriptionRepository subscriptions,
                             BillingInvoiceRepository invoices,
                             BillingEventRepository events) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.events = events;
    }

    // ---- subscriptions ---------------------------------------------------

    /**
     * The account's live subscription, or 404 if it has none.
     *
     * <p>404 rather than an empty body: "this account has never subscribed" is a real answer the
     * caller must handle, and an empty 200 makes it indistinguishable from a serialisation bug.
     */
    @GetMapping("/subscriptions/current")
    public Subscription current(@RequestParam UUID accountId) {
        return subscriptions
                .findFirstByAccountIdAndStatusNotOrderByCreatedAtDesc(accountId, "cancelled")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription"));
    }

    @GetMapping("/subscriptions")
    public List<Subscription> list(@RequestParam(required = false) UUID accountId,
                                   @RequestParam(required = false) String provider,
                                   @RequestParam(required = false) String providerRef) {
        // Lookup by provider ref is how a webhook finds the subscription an event refers to.
        if (provider != null && providerRef != null) {
            return subscriptions.findByProviderAndProviderRef(provider, providerRef)
                    .map(List::of).orElseGet(List::of);
        }
        if (accountId != null) {
            return subscriptions.findByAccountIdOrderByCreatedAtDesc(accountId);
        }
        return subscriptions.findAll();
    }

    @GetMapping("/subscriptions/{id}")
    public Subscription findById(@PathVariable UUID id) {
        return subscriptions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public Subscription create(@RequestBody Subscription subscription) {
        if (subscription.getAccountId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId is required");
        }
        if (subscription.getPlan() == null || subscription.getPlan().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plan is required");
        }
        subscription.setId(null);
        try {
            return subscriptions.save(subscription);
        } catch (RuntimeException e) {
            // The partial unique index allows one non-cancelled subscription per account. Saying
            // so beats a 500 on a constraint name the caller cannot interpret.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That account already has a live subscription");
        }
    }

    /**
     * Updates a subscription in place.
     *
     * <p>Null-guarded on the period and provider fields so a partial update — a pause that sets
     * only status and pausedAt — cannot blank the billing period. The flags and timestamps that
     * represent a state change are NOT guarded, because clearing them is a meaningful act:
     * resuming must be able to null {@code pausedAt}, and reversing a cancellation must be able to
     * set {@code cancelAtPeriodEnd} back to false.
     */
    @PutMapping("/subscriptions/{id}")
    public Subscription update(@PathVariable UUID id, @RequestBody Subscription incoming) {
        Subscription existing = subscriptions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));

        if (incoming.getPlan() != null && !incoming.getPlan().isBlank()) {
            existing.setPlan(incoming.getPlan());
        }
        if (incoming.getStatus() != null && !incoming.getStatus().isBlank()) {
            existing.setStatus(incoming.getStatus());
        }
        if (incoming.getProvider() != null && !incoming.getProvider().isBlank()) {
            existing.setProvider(incoming.getProvider());
        }
        if (incoming.getProviderRef() != null) {
            existing.setProviderRef(incoming.getProviderRef());
        }
        if (incoming.getCurrentPeriodStart() != null) {
            existing.setCurrentPeriodStart(incoming.getCurrentPeriodStart());
        }
        if (incoming.getCurrentPeriodEnd() != null) {
            existing.setCurrentPeriodEnd(incoming.getCurrentPeriodEnd());
        }
        if (incoming.getTrialEndsAt() != null) {
            existing.setTrialEndsAt(incoming.getTrialEndsAt());
        }
        // Unguarded on purpose — see the Javadoc. These carry the state change itself.
        existing.setCancelAtPeriodEnd(incoming.isCancelAtPeriodEnd());
        existing.setCancelledAt(incoming.getCancelledAt());
        existing.setPausedAt(incoming.getPausedAt());
        return subscriptions.save(existing);
    }

    // ---- invoices --------------------------------------------------------

    @GetMapping("/invoices")
    public List<BillingInvoice> invoices(@RequestParam(required = false) UUID accountId,
                                         @RequestParam(required = false) String provider,
                                         @RequestParam(required = false) String providerRef) {
        if (provider != null && providerRef != null) {
            return invoices.findByProviderAndProviderRef(provider, providerRef)
                    .map(List::of).orElseGet(List::of);
        }
        if (accountId != null) {
            return invoices.findByAccountIdOrderByCreatedAtDesc(accountId);
        }
        return invoices.findAll();
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingInvoice createInvoice(@RequestBody BillingInvoice invoice) {
        if (invoice.getAccountId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId is required");
        }
        if (invoice.getAmountCents() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountCents is required");
        }
        invoice.setId(null);
        return invoices.save(invoice);
    }

    @PutMapping("/invoices/{id}")
    public BillingInvoice updateInvoice(@PathVariable UUID id, @RequestBody BillingInvoice incoming) {
        BillingInvoice existing = invoices.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (incoming.getStatus() != null && !incoming.getStatus().isBlank()) {
            existing.setStatus(incoming.getStatus());
        }
        if (incoming.getPaidAt() != null) {
            existing.setPaidAt(incoming.getPaidAt());
        }
        if (incoming.getIssuedAt() != null) {
            existing.setIssuedAt(incoming.getIssuedAt());
        }
        return invoices.save(existing);
    }

    // ---- webhook events (the replay guard) -------------------------------

    /** Lookup by provider event id. An empty list means "not seen before". */
    @GetMapping("/events")
    public List<BillingEvent> events(@RequestParam String provider, @RequestParam String eventId) {
        return events.findByProviderAndEventId(provider, eventId).map(List::of).orElseGet(List::of);
    }

    /**
     * Records an event before it is applied.
     *
     * <p>A duplicate is rejected with 409 by the unique index on {@code (provider, event_id)}.
     * That rejection is the guard working: the caller treats it as "someone else has this one" and
     * declines to apply it, which is what makes two simultaneous deliveries safe rather than
     * racing through a check-then-act gap.
     */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingEvent recordEvent(@RequestBody BillingEvent event) {
        if (event.getProvider() == null || event.getEventId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider and eventId are required");
        }
        event.setId(null);
        try {
            return events.save(event);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event already recorded");
        }
    }
}
