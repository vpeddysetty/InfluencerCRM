package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.identity.application.DeletionRequestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Where deletion requests arrive and where they are approved.
 *
 * <p>Two endpoints, both necessarily unauthenticated, for different reasons: SES cannot present a
 * session, and the operator clicks the approval link from an email client that has none. Each
 * carries its own proof instead — a verified SNS signature, and a single-use token.
 */
@RestController
@RequestMapping("/api/deletion-requests")
public class DeletionRequestController {

    private static final Logger log = LoggerFactory.getLogger(DeletionRequestController.class);

    private final DeletionRequestService service;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public DeletionRequestController(DeletionRequestService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * SNS delivers a notification here when SES receives a message.
     *
     * <p><b>Why this is not authenticated.</b> SNS posts from AWS infrastructure and cannot hold a
     * session or a bearer token. The protection is the subscription confirmation handshake plus the
     * {@code SubscribeURL} check below: only a genuine SNS message names an {@code sns.amazonaws.com}
     * URL under this account's topic, and confirming any other URL would subscribe us to a stranger's
     * topic.
     *
     * <p><b>It always answers 200.</b> SNS retries a non-2xx, and a retry storm on a malformed
     * message would produce a notification flood without fixing anything. Failures are logged and
     * the raw message stays in the intake bucket for 90 days.
     */
    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> receive(@RequestBody String rawBody) {
        try {
            JsonNode envelope = objectMapper.readTree(rawBody);
            String type = text(envelope, "Type");

            if ("SubscriptionConfirmation".equals(type)) {
                confirmSubscription(envelope);
                return ResponseEntity.ok("subscription confirmed");
            }
            if (!"Notification".equals(type)) {
                log.info("Ignoring SNS message of type {}", type);
                return ResponseEntity.ok("ignored");
            }

            // The SES notification is JSON inside the SNS Message field, as a string.
            JsonNode ses = objectMapper.readTree(text(envelope, "Message"));
            JsonNode mail = ses.path("mail");
            JsonNode receipt = ses.path("receipt");

            String messageId = mail.path("messageId").asText(null);
            if (messageId == null) {
                log.error("SES notification with no messageId; ignoring");
                return ResponseEntity.ok("no message id");
            }

            // The rule writes to inbound/ -- see deletion-intake.tf.
            String key = "inbound/" + messageId;

            String from = header(mail, "From");
            String subject = header(mail, "Subject");
            if (subject == null) {
                subject = mail.path("commonHeaders").path("subject").asText(null);
            }
            if (from == null) {
                JsonNode common = mail.path("commonHeaders").path("from");
                from = common.isArray() && !common.isEmpty() ? common.get(0).asText() : null;
            }

            // Spam and virus verdicts are recorded, not enforced. A deletion request that trips a
            // spam filter is still a request the law obliges us to honour, and the operator sees
            // the message before approving anything.
            String spam = receipt.path("spamVerdict").path("status").asText("");
            String virus = receipt.path("virusVerdict").path("status").asText("");
            if (!spam.isEmpty() && !"PASS".equals(spam)) {
                log.warn("Deletion request {} has spam verdict {}", messageId, spam);
            }
            if (!virus.isEmpty() && !"PASS".equals(virus)) {
                log.warn("Deletion request {} has virus verdict {}", messageId, virus);
            }

            // The body is not in the notification -- it can exceed the SNS size limit, which is why
            // the rule stores the message in S3 and this carries only headers. Triage therefore
            // runs on the subject alone, and the operator reads the message itself.
            service.intake(from, subject, null, key);
            return ResponseEntity.ok("received");
        } catch (Exception e) {
            // Deliberately swallowed. See the method comment: a retry cannot fix a malformed
            // message and the object is still in the bucket.
            log.error("Could not process an SNS deletion notification: {}", e.toString());
            return ResponseEntity.ok("error logged");
        }
    }

    /**
     * The operator's approval link. <b>This is what actually deletes data.</b>
     *
     * <p>GET because it is clicked from an email, which is a real trade-off: a GET that mutates can
     * be triggered by a prefetching client. The token is single use and expires, the operator's
     * mailbox is the only place it exists, and the alternative — a form the operator must open and
     * submit — adds a step to a rarely-used path. The mitigation that matters is that every refusal
     * happens before anything is destroyed.
     *
     * <p>Returns HTML rather than JSON: the reader is a person in a browser, not a client.
     */
    @GetMapping(value = "/approve", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> approve(@RequestParam String token) {
        DeletionRequestService.Outcome outcome = service.approve(token);
        String heading = outcome.deleted() ? "Deletion carried out" : "Request refused";
        return ResponseEntity.ok(page(heading, outcome.note(), outcome.requestId().toString()));
    }

    // -----------------------------------------------------------------------

    private void confirmSubscription(JsonNode envelope) throws Exception {
        String subscribeUrl = text(envelope, "SubscribeURL");
        if (subscribeUrl == null) {
            log.error("SubscriptionConfirmation with no SubscribeURL");
            return;
        }
        // Confirming an arbitrary URL would subscribe this endpoint to somebody else's topic, which
        // is how a stranger gets our service to fetch a URL of their choosing. Only an
        // sns.amazonaws.com host over HTTPS is honoured.
        URI uri = URI.create(subscribeUrl);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !(host.endsWith(".amazonaws.com") && host.startsWith("sns."))) {
            log.error("Refusing to confirm a subscription to an unexpected host: {}", host);
            return;
        }
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        log.info("Confirmed SNS subscription: HTTP {}", response.statusCode());
    }

    private static String text(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.get(field).asText();
    }

    /** Reads one header out of the SES notification's header array. */
    private static String header(JsonNode mail, String name) {
        JsonNode headers = mail.path("headers");
        if (!headers.isArray()) {
            return null;
        }
        for (JsonNode header : headers) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText(null);
            }
        }
        return null;
    }

    private static String page(String heading, String detail, String reference) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title>
                <style>
                  body{font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
                       max-width:640px;margin:0 auto;padding:48px 24px;color:#1a1d21;background:#fff}
                  h1{font-size:24px;margin:0 0 16px}
                  .ref{color:#5b6470;font-size:14px;margin-top:24px}
                  @media (prefers-color-scheme:dark){body{background:#14171a;color:#e6e9ed}
                    .ref{color:#9aa4b0}}
                </style></head>
                <body>
                  <h1>%s</h1>
                  <p>%s</p>
                  <p class="ref">Reference: %s</p>
                </body></html>
                """.formatted(escape(heading), escape(heading), escape(detail), escape(reference));
    }

    /** The note carries an email address and a provider name; neither is trusted into HTML. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
