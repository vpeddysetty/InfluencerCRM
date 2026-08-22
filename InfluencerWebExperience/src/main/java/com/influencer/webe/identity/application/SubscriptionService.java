package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.billing.BillingProvider;
import com.influencer.webe.billing.BillingProviderRegistry;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Subscribing, pausing, resuming and cancelling (roadmap M2.1/M2.2).
 *
 * <p><b>The one invariant this class exists to hold:</b> {@code accounts.plan} — which
 * {@link PlanPolicy} enforces on every creation — must always equal
 * {@link SubscriptionState#effectivePlan}. Every method that changes a subscription's status
 * therefore writes the account's plan in the same call. If those two drift, either a paying
 * customer is refused or a cancelled one keeps paid limits, and neither surfaces as an error
 * anywhere; the account simply behaves wrongly until someone notices.
 *
 * <p><b>Authorization is not here.</b> Who may cancel is {@code RolePermissions} —
 * {@code ACCOUNT_BILLING} for changes (OWNER only), {@code ACCOUNT_BILLING_READ} for viewing
 * (OWNER and ADMIN) — checked at the controller. This class assumes the caller has already been
 * cleared, exactly as the other application services do.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** A month, for the manual provider's period. A real provider tells us its own dates. */
    private static final Duration MANUAL_PERIOD = Duration.ofDays(30);

    /** The yearly equivalent, for the same reason. */
    private static final Duration MANUAL_YEAR = Duration.ofDays(365);

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final BillingProviderRegistry providers;
    private final String uiBaseUrl;

    public SubscriptionService(DaoGatewayClient dao, ResponseShapeService shape,
                               BillingProviderRegistry providers,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${web-experience.ui-base-url}") String uiBaseUrl) {
        this.dao = dao;
        this.shape = shape;
        this.providers = providers;
        // FIRST value only. ui-base-url may be a comma-separated list because the same site
        // is served from several hostnames and CORS must allow them all; this builds
        // a checkout return URL the provider redirects to, which needs exactly one.
        // Verified live 2026-08-22: the whole string produced
        // "https://tejdux.com,https://www.tejdux.com/verify-email?token=..." - not a link.
        this.uiBaseUrl = uiBaseUrl == null ? ""
                : uiBaseUrl.split(",")[0].trim().replaceAll("/+$", "");
    }

    // ---- reading ---------------------------------------------------------

    /** The live subscription, or null when the account has never subscribed. */
    public JsonNode current(UUID accountId) {
        try {
            return dao.get("/billing/subscriptions/current", Map.of("accountId", accountId.toString()));
        } catch (RuntimeException e) {
            // 404 is the ordinary answer for a free account, not a failure.
            return null;
        }
    }

    public JsonNode invoices(UUID accountId) {
        JsonNode rows = dao.get("/billing/invoices", Map.of("accountId", accountId.toString()));
        return rows == null ? shape.objectMapper().createArrayNode() : rows;
    }

    // ---- subscribing -----------------------------------------------------

    /**
     * Starts a subscription to {@code plan}.
     *
     * <p>The row is created BEFORE the provider is called, so its id can serve as the idempotency
     * key — the same discipline {@code PayoutService} follows. A key generated after the call
     * would differ on a retry and could produce a second subscription and a second charge.
     *
     * <p>The account's plan is only raised once the provider says the subscription is live. A
     * hosted-checkout provider returns {@code activated=false} because the user has not paid yet;
     * raising the plan there would grant paid limits to anyone who clicked subscribe and then
     * closed the tab.
     */
    public JsonNode subscribe(UUID accountId, String plan, String billingInterval,
                              String successUrl, String cancelUrl) {
        PlanPolicy target = PlanPolicy.forKey(plan);
        if (target == PlanPolicy.FREE) {
            // Guarded because PlanPolicy.forKey falls back to FREE on anything unrecognised, so
            // without this a typo would silently "subscribe" someone to the free tier.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose a paid plan. To move down to free, cancel the current subscription.");
        }
        if (current(accountId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account already has a subscription. Change the plan on it instead.");
        }

        BillingProvider provider = providers.active();
        // Defaults to monthly on anything unrecognised. Billing someone yearly because a request
        // carried a typo would take twelve times what they agreed to.
        BillingProvider.BillingInterval interval =
                BillingProvider.BillingInterval.forKey(billingInterval);

        // A trial is the one status that grants paid limits with nothing paid, so it is only
        // opened when something will close it. Both halves below matter: the plan must be offered
        // a trial at all, and the provider must be able to end it. Stripe ends its own and emits
        // customer.subscription.updated; the manual provider emits nothing, and a trial recorded
        // under it would sit entitled forever because SubscriptionState treats trialing as
        // entitled and nothing revisits it.
        //
        // Without a trial the row starts PAUSED, which is the only non-terminal status that grants
        // no paid plan and can still reach active — exactly the "exists but is not billing yet"
        // shape needed here. It is a moment long for a provider that activates immediately, and
        // the honest state for one that does not.
        int trialDays = provider.trialDays(target.key());
        boolean trialing = trialDays > 0 && provider.capabilities().expiresTrials();
        if (trialDays > 0 && !provider.capabilities().expiresTrials()) {
            log.warn("[billing:{}] a {}-day trial is configured for plan '{}' but this provider "
                            + "cannot end trials, so none was started for account {}",
                    provider.key(), trialDays, target.key(), accountId);
        }

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("accountId", accountId.toString());
        body.put("plan", target.key());
        body.put("status", trialing ? SubscriptionState.TRIALING : SubscriptionState.PAUSED);
        body.put("provider", provider.key());
        // NOTE the interval is deliberately NOT sent to the DAO: identity.subscriptions has no
        // column for it, so the field would be silently dropped, and a value that looks stored
        // but never arrives is worse than no value at all. It reaches Stripe through the price
        // id — the thing that actually bills — and current_period_end shows the cadence in our
        // own data. Persisting it needs a migration first.
        if (trialing) {
            // The expectation, not the authority. Stripe's own webhook carries the real end date;
            // this is recorded so a trial is visible in our data before any event lands, and so an
            // operator can answer "when does this end" without calling Stripe.
            body.put("trialEndsAt", Instant.now().plus(Duration.ofDays(trialDays)).toString());
        }
        JsonNode created = dao.post("/billing/subscriptions", body);
        String subscriptionId = created.path("id").asText();

        // Built here from the configured UI origin, NOT taken from the caller. A redirect URL a
        // client can set is an open redirect: the provider would send the user wherever the
        // request said, and a payment flow is the most credible possible context for that. The
        // parameters are accepted only so a caller can name a path within our own origin.
        BillingProvider.CheckoutSession session = provider.startCheckout(
                subscriptionId, accountId.toString(), target.key(), interval,
                safeReturnUrl(successUrl, "/billing?checkout=success"),
                safeReturnUrl(cancelUrl, "/billing?checkout=cancelled"));

        ObjectNode update = created.deepCopy();
        if (session.providerRef() != null) {
            update.put("providerRef", session.providerRef());
        }
        if (session.activated()) {
            Instant now = Instant.now();
            update.put("status", SubscriptionState.ACTIVE);
            update.put("currentPeriodStart", now.toString());
            update.put("currentPeriodEnd", now.plus(
                    interval == BillingProvider.BillingInterval.YEARLY
                            ? MANUAL_YEAR : MANUAL_PERIOD).toString());
        }
        JsonNode saved = dao.put("/billing/subscriptions/" + subscriptionId, update);

        syncAccountPlan(accountId, saved);

        ObjectNode out = (ObjectNode) describe(saved, provider);
        out.put("checkoutUrl", session.checkoutUrl());
        out.put("detail", session.detail());
        return out;
    }

    // ---- lifecycle -------------------------------------------------------

    /**
     * Suspends billing, keeping the subscription resumable.
     *
     * <p>The account drops to free limits while paused — that is the point — but the subscription
     * row keeps its paid plan, which is what resume restores. Existing data is untouched: pausing
     * is not a downgrade of what you have, only of what you may add.
     */
    public JsonNode pause(UUID accountId) {
        JsonNode subscription = requireLive(accountId);
        String status = subscription.path("status").asText("");
        if (!SubscriptionState.canPause(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, cannotPause(status));
        }

        BillingProvider provider = providerFor(subscription);
        BillingProvider.Result result = provider.pause(subscription.path("providerRef").asText(null));
        if (!result.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not pause the subscription: " + result.detail());
        }

        ObjectNode update = subscription.deepCopy();
        update.put("status", SubscriptionState.PAUSED);
        update.put("pausedAt", Instant.now().toString());
        JsonNode saved = dao.put("/billing/subscriptions/" + subscription.path("id").asText(), update);
        syncAccountPlan(accountId, saved);
        return describe(saved, provider);
    }

    public JsonNode resume(UUID accountId) {
        JsonNode subscription = requireLive(accountId);
        if (!SubscriptionState.canResume(subscription.path("status").asText(""))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a paused subscription can be resumed.");
        }

        BillingProvider provider = providerFor(subscription);
        BillingProvider.Result result = provider.resume(subscription.path("providerRef").asText(null));
        if (!result.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not resume the subscription: " + result.detail());
        }

        Instant now = Instant.now();
        ObjectNode update = subscription.deepCopy();
        update.put("status", SubscriptionState.ACTIVE);
        update.putNull("pausedAt");
        update.put("currentPeriodStart", now.toString());
        update.put("currentPeriodEnd", now.plus(MANUAL_PERIOD).toString());
        JsonNode saved = dao.put("/billing/subscriptions/" + subscription.path("id").asText(), update);
        syncAccountPlan(accountId, saved);
        return describe(saved, provider);
    }

    /**
     * Cancels at the end of the paid period.
     *
     * <p><b>Access is not withdrawn immediately.</b> The subscription stays active until
     * {@code currentPeriodEnd}; only then does the plan drop. Confiscating time already paid for
     * would be taking something the customer bought, and the roadmap names the working cancel
     * button as the product feature — the competitor's most-cited complaint is cancellation being
     * "impossible to stop", so this path has to be plainly better than theirs.
     *
     * <p>An immediate cancel is available via {@code immediate=true} for the case where a customer
     * explicitly wants out now.
     */
    public JsonNode cancel(UUID accountId, boolean immediate) {
        JsonNode subscription = requireLive(accountId);
        String status = subscription.path("status").asText("");
        if (!SubscriptionState.canCancel(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This subscription is already cancelled.");
        }

        BillingProvider provider = providerFor(subscription);
        BillingProvider.Result result = provider.cancel(subscription.path("providerRef").asText(null));
        if (!result.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not cancel the subscription: " + result.detail());
        }

        Instant now = Instant.now();
        ObjectNode update = subscription.deepCopy();
        // A paused subscription has no remaining paid time to honour, so cancelling one takes
        // effect at once regardless — waiting for a period end that is not running would leave it
        // in limbo forever.
        boolean endsNow = immediate
                || SubscriptionState.PAUSED.equals(status)
                || !subscription.hasNonNull("currentPeriodEnd");

        if (endsNow) {
            update.put("status", SubscriptionState.CANCELLED);
            update.put("cancelledAt", now.toString());
            update.put("cancelAtPeriodEnd", false);
        } else {
            // Still active. The flag is what the UI reads to say exactly when access ends.
            update.put("cancelAtPeriodEnd", true);
        }

        JsonNode saved = dao.put("/billing/subscriptions/" + subscription.path("id").asText(), update);
        syncAccountPlan(accountId, saved);
        return describe(saved, provider);
    }

    // ---- internals -------------------------------------------------------

    /**
     * Writes {@code accounts.plan} to match the subscription.
     *
     * <p>The invariant named in the class Javadoc. Failure is logged loudly rather than swallowed:
     * the subscription change already happened, so throwing here would report failure for
     * something that succeeded — but a drift between the two is exactly the silent wrongness this
     * method exists to prevent, so it must not pass unnoticed.
     */
    private void syncAccountPlan(UUID accountId, JsonNode subscription) {
        String effective = SubscriptionState.effectivePlan(
                subscription.path("plan").asText(null), subscription.path("status").asText(null));
        try {
            dao.patch("/tenancy/accounts/" + accountId,
                    shape.objectMapper().createObjectNode().put("plan", effective));
        } catch (RuntimeException e) {
            log.error("Subscription for account {} moved to status '{}' but accounts.plan could not be "
                            + "set to '{}'. Entitlements are now out of step with billing: {}",
                    accountId, subscription.path("status").asText(""), effective, e.toString());
        }
    }

    /**
     * A return URL guaranteed to be on our own origin.
     *
     * <p>Accepts only a relative path from the caller and prefixes the configured UI base URL. An
     * absolute URL is discarded rather than sanitised — there is no legitimate reason for a
     * checkout to return anywhere else, and a payment provider redirecting a user to an
     * attacker-named site immediately after they entered card details is about the most credible
     * phishing hand-off there is.
     *
     * <p>Protocol-relative paths ({@code //evil.example}) are caught too: they look relative and
     * resolve to a different host.
     */
    String safeReturnUrl(String requested, String fallbackPath) {
        String path = fallbackPath;
        if (requested != null && requested.startsWith("/") && !requested.startsWith("//")) {
            path = requested;
        }
        return uiBaseUrl + path;
    }

    private JsonNode requireLive(UUID accountId) {
        JsonNode subscription = current(accountId);
        if (subscription == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "This account has no subscription.");
        }
        return subscription;
    }

    private BillingProvider providerFor(JsonNode subscription) {
        return providers.find(subscription.path("provider").asText("manual"))
                .orElseGet(providers::active);
    }

    /** Adds the derived facts a UI needs but the row does not store. */
    private JsonNode describe(JsonNode subscription, BillingProvider provider) {
        ObjectNode out = subscription.deepCopy();
        String status = subscription.path("status").asText("");
        out.put("statusLabel", SubscriptionState.label(status));
        out.put("effectivePlan", SubscriptionState.effectivePlan(
                subscription.path("plan").asText(null), status));
        out.put("canPause", SubscriptionState.canPause(status));
        out.put("canResume", SubscriptionState.canResume(status));
        out.put("canCancel", SubscriptionState.canCancel(status));
        // So the UI can never present an unpaid subscription as a paid one.
        out.put("chargesMoney", provider.capabilities().chargesMoney());
        out.put("providerName", provider.displayName());
        return out;
    }

    private static String cannotPause(String status) {
        if (SubscriptionState.PAUSED.equals(status)) {
            return "This subscription is already paused.";
        }
        if (SubscriptionState.PAST_DUE.equals(status)) {
            // Refused rather than allowed, because pausing would look like a way to stop the
            // retries and it is not — the outstanding charge is still owed.
            return "There is an unpaid charge on this subscription. Update the payment method, or "
                    + "cancel — pausing does not clear what is owed.";
        }
        return "This subscription cannot be paused from its current state.";
    }
}
