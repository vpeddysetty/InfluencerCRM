package com.influencer.webe.billing.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.billing.BillingProvider;
import com.influencer.webe.shared.infrastructure.OutboundHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stripe Checkout + Billing (roadmap M2.1).
 *
 * <p><b>Hosted checkout, hosted portal — no billing UI of our own.</b> That is the roadmap's
 * explicit instruction for 2.1, and it is also what keeps card data out of this system entirely:
 * the customer enters it on Stripe's page, and this adapter never sees a PAN, a CVV, or an expiry.
 * Reimplementing either surface would put us in PCI scope for no gain.
 *
 * <p><b>The REST API directly rather than the Stripe SDK.</b> Three endpoints are needed, all
 * form-encoded POSTs; the SDK would be a large dependency, a version to track, and a second
 * HTTP/retry/timeout policy alongside {@code OutboundHttpClient}. The YouTube adapter took the
 * same route for the same reason.
 *
 * <p><b>{@code isConfigured()} gates activation.</b> With no secret key this bean still exists but
 * reports itself unconfigured, and the registry falls back to {@code manual} — which announces
 * that it takes no money. A half-configured Stripe adapter must never look like a working one.
 *
 * <p><b>Price ids, not prices.</b> The amount lives in Stripe's own catalogue and is referenced by
 * id. Putting a number here would let the code and the thing that actually charges disagree, and
 * the customer's card is the tiebreaker.
 */
@Component
@ConditionalOnProperty(name = "web-experience.billing.provider", havingValue = "stripe")
public class StripeBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingProvider.class);

    private static final String PROVIDER = "stripe";
    private static final String API = "https://api.stripe.com/v1";

    private final OutboundHttpClient http;
    private final String secretKey;
    private final Map<String, String> priceIdByPlan;

    public StripeBillingProvider(
            OutboundHttpClient http,
            @Value("${web-experience.billing.stripe.secret-key:}") String secretKey,
            @Value("${web-experience.billing.stripe.price-pro:}") String pricePro,
            @Value("${web-experience.billing.stripe.price-agency:}") String priceAgency) {
        this.http = http;
        this.secretKey = secretKey == null ? "" : secretKey.trim();

        Map<String, String> prices = new LinkedHashMap<>();
        if (pricePro != null && !pricePro.isBlank()) {
            prices.put("pro", pricePro.trim());
        }
        if (priceAgency != null && !priceAgency.isBlank()) {
            prices.put("agency", priceAgency.trim());
        }
        this.priceIdByPlan = Map.copyOf(prices);

        if (this.secretKey.isEmpty()) {
            log.warn("[billing:stripe] selected as the billing provider but no secret key is set. "
                    + "Set web-experience.billing.stripe.secret-key — subscriptions will fall back "
                    + "to the manual provider, which takes no money.");
        } else if (this.secretKey.startsWith("sk_test_")) {
            // Stated at startup so a test-mode deployment is never mistaken for a live one. A
            // sandbox key charges nobody, and an operator seeing "subscribed" needs to know which
            // mode produced it.
            log.warn("[billing:stripe] running in TEST MODE (sk_test_…). No real money will move.");
        }
        if (priceIdByPlan.isEmpty()) {
            log.warn("[billing:stripe] no price ids configured. Set "
                    + "web-experience.billing.stripe.price-pro / price-agency to the ids from your "
                    + "Stripe product catalogue.");
        }
    }

    @Override
    public String key() {
        return PROVIDER;
    }

    @Override
    public String displayName() {
        return isTestMode() ? "Stripe (test mode)" : "Stripe";
    }

    /**
     * Whether this adapter can actually reach Stripe.
     *
     * <p>Needs both a key and at least one price id: a key alone cannot start a subscription to
     * anything, and reporting configured in that state would produce a checkout that always fails.
     */
    public boolean isConfigured() {
        return !secretKey.isEmpty() && !priceIdByPlan.isEmpty();
    }

    /** Test-mode keys are prefixed by Stripe itself, so this needs no separate flag to get wrong. */
    public boolean isTestMode() {
        return secretKey.startsWith("sk_test_");
    }

    @Override
    public Capabilities capabilities() {
        // chargesMoney tracks isConfigured(), not merely "this class is present". An unconfigured
        // Stripe adapter takes no money and must say so, exactly like the manual one.
        //
        // NOTE it is true in test mode. A sandbox key does move a (fake) card through a real
        // charge flow, so the honest answer is that this adapter charges — displayName() and the
        // startup log carry the test/live distinction instead.
        return new Capabilities(isConfigured(), true, true);
    }

    @Override
    public CheckoutSession startCheckout(String idempotencyKey, String accountId, String plan,
                                         String successUrl, String cancelUrl) {
        String priceId = priceIdByPlan.get(String.valueOf(plan).toLowerCase(Locale.ROOT));
        if (!isConfigured() || priceId == null) {
            return new CheckoutSession(null, null, false,
                    "Stripe is not configured for the " + plan + " plan.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "subscription");
        form.put("line_items[0][price]", priceId);
        form.put("line_items[0][quantity]", "1");
        form.put("success_url", successUrl);
        form.put("cancel_url", cancelUrl);
        // Carried back on every webhook, which is how an event is matched to an account without
        // trusting anything the payload claims about who it belongs to.
        form.put("client_reference_id", idempotencyKey);
        form.put("metadata[accountId]", accountId);
        form.put("metadata[subscriptionId]", idempotencyKey);
        form.put("metadata[plan]", plan);

        OutboundHttpClient.Response response =
                http.postForm(API + "/checkout/sessions", form, authHeaders(idempotencyKey));

        if (!response.ok()) {
            return new CheckoutSession(null, null, false, stripeError(response.body()));
        }

        JsonNode body = response.body();
        return new CheckoutSession(
                body.path("url").asText(null),
                // The Checkout session id, not the subscription id — the subscription does not
                // exist until the customer pays. checkout.session.completed carries the real one.
                body.path("id").asText(null),
                // NOT activated. The user has not paid yet; they have only been given a page. A
                // hosted-checkout provider that activated here would grant a paid plan to anyone
                // who clicked subscribe and closed the tab.
                false,
                "Complete the payment on Stripe to activate this plan.");
    }

    @Override
    public String portalUrl(String providerRef, String returnUrl) {
        if (!isConfigured() || providerRef == null || providerRef.isBlank()) {
            return null;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("customer", providerRef);
        form.put("return_url", returnUrl);

        OutboundHttpClient.Response response =
                http.postForm(API + "/billing_portal/sessions", form, authHeaders(null));
        return response.ok() ? response.body().path("url").asText(null) : null;
    }

    /**
     * Cancels at the end of the paid period.
     *
     * <p>{@code cancel_at_period_end=true} rather than a delete: the customer keeps the time they
     * paid for. An immediate delete would take back something already bought, which is the
     * behaviour this product positions against.
     */
    @Override
    public Result cancel(String providerRef) {
        if (!isConfigured() || providerRef == null || providerRef.isBlank()) {
            return Result.failed(PROVIDER, "No Stripe subscription reference to cancel.");
        }
        OutboundHttpClient.Response response = http.postForm(
                API + "/subscriptions/" + providerRef,
                Map.of("cancel_at_period_end", "true"),
                authHeaders(null));
        return response.ok()
                ? Result.ok(PROVIDER, "Cancelled at the end of the paid period.")
                : Result.failed(PROVIDER, stripeError(response.body()));
    }

    /**
     * Pauses collection, keeping the subscription in place.
     *
     * <p>{@code pause_collection[behavior]=void} stops invoices being generated without ending the
     * subscription, so resuming does not mean re-subscribing. {@code keep_as_draft} would leave
     * invoices accruing to be collected later, which is not what a customer means by "pause".
     */
    @Override
    public Result pause(String providerRef) {
        if (!isConfigured() || providerRef == null || providerRef.isBlank()) {
            return Result.failed(PROVIDER, "No Stripe subscription reference to pause.");
        }
        OutboundHttpClient.Response response = http.postForm(
                API + "/subscriptions/" + providerRef,
                Map.of("pause_collection[behavior]", "void"),
                authHeaders(null));
        return response.ok()
                ? Result.ok(PROVIDER, "Billing paused at Stripe.")
                : Result.failed(PROVIDER, stripeError(response.body()));
    }

    @Override
    public Result resume(String providerRef) {
        if (!isConfigured() || providerRef == null || providerRef.isBlank()) {
            return Result.failed(PROVIDER, "No Stripe subscription reference to resume.");
        }
        // An empty value clears pause_collection, which is how Stripe expresses "resume".
        OutboundHttpClient.Response response = http.postForm(
                API + "/subscriptions/" + providerRef,
                Map.of("pause_collection", ""),
                authHeaders(null));
        return response.ok()
                ? Result.ok(PROVIDER, "Billing resumed at Stripe.")
                : Result.failed(PROVIDER, stripeError(response.body()));
    }

    // ---- internals -------------------------------------------------------

    /**
     * Bearer auth, plus Stripe's idempotency header where one applies.
     *
     * <p>{@code Idempotency-Key} is what makes a retry after a timeout safe: Stripe replays the
     * original response instead of creating a second subscription. The key is the subscription
     * row's id, generated and persisted before the call, so it is stable across the retry — a key
     * generated here would differ each time and defeat the entire mechanism.
     */
    private Map<String, String> authHeaders(String idempotencyKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + secretKey);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    /**
     * The message Stripe gave, or a generic one.
     *
     * <p>Stripe's own wording ("Your card was declined") is more useful to a customer than
     * anything invented here, and it is written to be shown.
     */
    private static String stripeError(JsonNode body) {
        String message = body == null ? null : body.path("error").path("message").asText(null);
        return message == null || message.isBlank()
                ? "Stripe rejected the request."
                : message;
    }
}
