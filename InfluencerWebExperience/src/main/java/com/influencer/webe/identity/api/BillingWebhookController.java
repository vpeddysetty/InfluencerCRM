package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.application.BillingWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives subscription events from a payment provider (roadmap M2.2).
 *
 * <p><b>Always answers 200, even when it declines to act.</b> A provider retries anything that is
 * not 2xx, so returning an error for a duplicate or an event type we do not handle would make it
 * redeliver the same thing indefinitely. What happened is in the body and the log instead.
 *
 * <p><b>Signature verification is the missing piece, and is deliberately not faked.</b> A real
 * provider signs each request with a shared secret, and this endpoint is unauthenticated by
 * necessity — the provider holds no user token. Until a provider and its signing secret exist,
 * {@code web-experience.billing.webhook-secret} is unset and the endpoint is <b>disabled</b> rather
 * than left open: an unauthenticated, unverified endpoint that moves accounts onto paid plans is a
 * hole, and an empty-string "verification" that always passes would be worse than none because it
 * would look done.
 */
@RestController
@RequestMapping("/api/billing/webhooks")
public class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    private final BillingWebhookService webhooks;
    private final String signingSecret;

    public BillingWebhookController(
            BillingWebhookService webhooks,
            @org.springframework.beans.factory.annotation.Value(
                    "${web-experience.billing.webhook-secret:}") String signingSecret) {
        this.webhooks = webhooks;
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
    }

    @PostMapping("/{provider}")
    public ResponseEntity<WebhookResponse> receive(
            @PathVariable String provider,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody JsonNode payload) {

        if (signingSecret.isEmpty()) {
            // 503, not 200: this is a configuration state the operator must fix, and a provider
            // retrying is the correct behaviour once it is fixed. Nothing is applied.
            log.warn("[billing:{}] webhook received but no signing secret is configured — refusing. "
                    + "Set web-experience.billing.webhook-secret.", provider);
            return ResponseEntity.status(503)
                    .body(new WebhookResponse(false, "Webhooks are not configured on this deployment."));
        }

        if (!verify(signature, payload)) {
            // 400 rather than 401: a provider does not re-authenticate, and retrying an unsigned
            // request would not help. This is a malformed request, not a credential problem.
            log.warn("[billing:{}] webhook signature did not verify — refusing", provider);
            return ResponseEntity.badRequest()
                    .body(new WebhookResponse(false, "Signature verification failed."));
        }

        BillingWebhookService.Outcome outcome = webhooks.handle(
                provider,
                payload.path("id").asText(null),
                payload.path("type").asText(null),
                payload.path("data"));

        return ResponseEntity.ok(new WebhookResponse(outcome.applied(), outcome.detail()));
    }

    /**
     * Verifies the provider's signature.
     *
     * <p>Deliberately unimplemented rather than stubbed to true. Each provider signs differently
     * (Stripe uses a timestamped HMAC-SHA256 over the raw body, and the RAW body matters — a
     * re-serialised {@code JsonNode} will not verify), so this is written when the provider is
     * known. Returning false until then keeps the endpoint closed; the guard above means it is not
     * even reached without a configured secret.
     */
    private boolean verify(String signature, JsonNode payload) {
        return false;
    }

    public record WebhookResponse(boolean applied, String detail) {
    }
}
