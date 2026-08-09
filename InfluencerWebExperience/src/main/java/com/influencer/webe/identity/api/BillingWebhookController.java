package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.billing.StripeSignature;
import com.influencer.webe.identity.application.BillingWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Receives subscription events from a payment provider (roadmap M2.2).
 *
 * <p><b>The signature is the authentication.</b> This endpoint cannot require a user token — a
 * payment provider holds none — so a verified signature is the only thing separating a real event
 * from anyone who knows the URL. Without it, a forged {@code subscription.updated} would move an
 * account onto the agency plan for free.
 *
 * <p><b>The raw body is taken as a String, deliberately.</b> Stripe signs the exact bytes it sent;
 * a body parsed to {@code JsonNode} and re-serialised differs in key order and whitespace and
 * would never verify. Parsing happens only after the signature checks out — which also means
 * unverified input is never handed to the JSON parser.
 *
 * <p><b>Answers 200 even when it declines to act.</b> A provider retries anything non-2xx, so
 * returning an error for a duplicate or an unhandled event type would make it redeliver forever.
 * The exceptions are the two states a retry can legitimately fix: no secret configured (503) and a
 * bad signature (400).
 */
@RestController
@RequestMapping("/api/billing/webhooks")
public class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    private final BillingWebhookService webhooks;
    private final ObjectMapper objectMapper;
    private final String signingSecret;

    public BillingWebhookController(
            BillingWebhookService webhooks,
            ObjectMapper objectMapper,
            @Value("${web-experience.billing.webhook-secret:}") String signingSecret) {
        this.webhooks = webhooks;
        this.objectMapper = objectMapper;
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
    }

    @PostMapping("/{provider}")
    public ResponseEntity<WebhookResponse> receive(
            @PathVariable String provider,
            // Stripe's own header name. The generic one is accepted too so a different provider
            // can be added without changing this signature.
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String genericSignature,
            @RequestBody String rawBody) {

        if (signingSecret.isEmpty()) {
            // 503 rather than 200: this is a configuration state an operator must fix, and a
            // provider retrying afterwards is the behaviour we want. Nothing is applied.
            log.warn("[billing:{}] webhook received but no signing secret is configured — refusing. "
                    + "Set web-experience.billing.webhook-secret.", provider);
            return ResponseEntity.status(503)
                    .body(new WebhookResponse(false, "Webhooks are not configured on this deployment."));
        }

        String signature = stripeSignature != null ? stripeSignature : genericSignature;
        if (!StripeSignature.verify(signature, rawBody, signingSecret, Instant.now())) {
            // 400, not 401: a provider does not re-authenticate, so retrying an unsigned request
            // would not help. This is a malformed request rather than a credential problem.
            //
            // No detail about WHY it failed — a caller probing the difference between a bad HMAC
            // and a stale timestamp learns something about the secret.
            log.warn("[billing:{}] webhook signature did not verify — refusing", provider);
            return ResponseEntity.badRequest()
                    .body(new WebhookResponse(false, "Signature verification failed."));
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            // Signed but unparseable. 400 rather than a retry loop: redelivering the same bytes
            // will not make them parse.
            log.warn("[billing:{}] webhook body was signed but could not be parsed: {}", provider, e.toString());
            return ResponseEntity.badRequest()
                    .body(new WebhookResponse(false, "Body could not be parsed."));
        }

        BillingWebhookService.Outcome outcome = webhooks.handle(
                provider,
                payload.path("id").asText(null),
                payload.path("type").asText(null),
                // Stripe nests the changed object under data.object; the generic shape puts it at
                // data. Preferring the former without requiring it keeps both providers working.
                payload.path("data").hasNonNull("object")
                        ? payload.path("data").path("object")
                        : payload.path("data"));

        return ResponseEntity.ok(new WebhookResponse(outcome.applied(), outcome.detail()));
    }

    public record WebhookResponse(boolean applied, String detail) {
    }
}
