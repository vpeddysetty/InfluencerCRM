package com.influencer.webe.shared.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.EmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Sends transactional email through Amazon SES v2.
 *
 * <p><b>Why SES.</b> The alternatives (Postmark, SendGrid) are easier to start with, but this
 * platform already runs on AWS — CloudFront for hosting, ACM for certificates — so SES needs no new
 * vendor relationship, no separate invoice, and its credentials sit in the same IAM model as
 * everything else. Deliverability is comparable once a domain is verified and DKIM is on.
 *
 * <p><b>REST API rather than the AWS SDK</b>, for the same reason as Stripe and YouTube: the SDK is
 * not in this offline build, and it would pull a large transitive tree to make one HTTP call. See
 * {@link AwsSigV4}, which is deliberately narrow enough to audit.
 *
 * <h2>Decisions worth keeping</h2>
 *
 * <p><b>It reports {@code sent} only when SES accepts the message.</b> Acceptance is not delivery —
 * a message can still bounce — but it is the strongest claim this code can honestly make. Returning
 * success on a 4xx would be the failure {@code LoggingEmailSender} exists to avoid: a provider
 * claiming to have sent something it did not.
 *
 * <p><b>It never throws.</b> {@code EmailPort.send} is contractually best-effort: an invitation is
 * already persisted by the time this runs, and an SES outage must not roll back the request that
 * created it.
 *
 * <p><b>Unconfigured means unconfigured.</b> The bean only exists when the provider is set to
 * {@code ses}; a missing key or sender address is reported at startup and every send fails loudly
 * rather than silently doing nothing. An email system that quietly sends nothing is the specific
 * thing this change exists to end.
 *
 * <p><b>The body is never logged.</b> {@code LoggingEmailSender} logs bodies deliberately so a
 * developer can follow an invitation link without a mail server — but those bodies carry one-time
 * tokens, and once a real provider is configured the same behaviour would be writing live
 * credentials to a log file that support tooling scrapes.
 */
@Component
@ConditionalOnProperty(name = "web-experience.email.provider", havingValue = "ses")
public class SesEmailSender implements EmailPort {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

    private static final String PROVIDER = "ses";
    private static final String SERVICE = "ses";
    private static final String PATH = "/v2/email/outbound-emails";

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final String region;
    private final String fromAddress;
    private final String configurationSet;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final InstanceRoleCredentials instanceRole;

    public SesEmailSender(
            @Value("${web-experience.email.ses.access-key-id:}") String accessKeyId,
            @Value("${web-experience.email.ses.secret-access-key:}") String secretAccessKey,
            @Value("${web-experience.email.ses.session-token:}") String sessionToken,
            @Value("${web-experience.email.ses.region:us-east-1}") String region,
            @Value("${web-experience.email.from:}") String fromAddress,
            @Value("${web-experience.email.ses.configuration-set:}") String configurationSet,
            ObjectMapper objectMapper) {

        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
        this.fromAddress = fromAddress;
        this.configurationSet = configurationSet;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.instanceRole = new InstanceRoleCredentials(this.httpClient, objectMapper);

        if (!isConfigured()) {
            // Loud at startup rather than at the first send: the first send is usually an
            // invitation someone is waiting for, and discovering the misconfiguration then means
            // the person who triggered it is the one who finds out.
            log.error("Email provider is 'ses' but it is not fully configured. Needs "
                    + "web-experience.email.from, plus EITHER "
                    + "web-experience.email.ses.access-key-id and .secret-access-key, OR an EC2 "
                    + "instance role granting ses:SendEmail. NO MAIL WILL BE SENT.");
        } else {
            log.info("Email provider: SES in {} sending as {}", this.region, this.fromAddress);
        }
    }

    /**
     * A key alone is not "configured".
     *
     * <p>The sender address matters as much as the credentials: SES rejects an unverified
     * {@code FromEmailAddress} outright, so a deployment with keys but no sender would fail every
     * send while looking configured. Same reasoning as {@code StripeBillingProvider} needing both a
     * secret key and a price id.
     */
    private boolean isConfigured() {
        // Either explicit keys or an instance role will do; the sender address is required either
        // way, because SES rejects an unverified FromEmailAddress outright.
        return notBlank(fromAddress)
                && ((notBlank(accessKeyId) && notBlank(secretAccessKey)) || instanceRole.current() != null);
    }

    /**
     * The credentials to sign with.
     *
     * <p>Static keys win when configured, so an operator who sets them explicitly gets what they
     * asked for. Otherwise the EC2 instance role is used — it already grants {@code ses:SendEmail},
     * and it is a strictly better credential than a long-lived IAM user secret sitting in
     * configuration: it rotates itself, expires, and cannot be used off the instance.
     */
    private InstanceRoleCredentials.Credentials resolveCredentials() {
        if (notBlank(accessKeyId) && notBlank(secretAccessKey)) {
            return new InstanceRoleCredentials.Credentials(
                    accessKeyId, secretAccessKey, sessionToken, java.time.Instant.MAX);
        }
        return instanceRole.current();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public Result send(Message message) {
        if (message == null || !notBlank(message.to())) {
            log.warn("[email:{}] not sent — no recipient address", PROVIDER);
            return Result.failed(PROVIDER, "no recipient address");
        }
        if (!isConfigured()) {
            return Result.failed(PROVIDER, "SES is not configured");
        }

        try {
            String body = buildRequest(message);
            String host = "email." + region + ".amazonaws.com";

            InstanceRoleCredentials.Credentials credentials = resolveCredentials();
            if (credentials == null) {
                return Result.failed(PROVIDER, "no AWS credentials available");
            }

            Map<String, String> headers = AwsSigV4.signJsonPost(
                    credentials.accessKeyId(), credentials.secretAccessKey(),
                    credentials.sessionToken(),
                    region, SERVICE, host, PATH, body, Instant.now());

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + host + PATH))
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(request::header);

            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String messageId = readMessageId(response.body());
                log.info("[email:{}] accepted for {} (id {})", PROVIDER, message.to(), messageId);
                return Result.sent(PROVIDER, messageId);
            }

            // The recipient is logged; the body is not. SES error responses are short and do not
            // echo the message, so this is safe to record and is what an operator needs.
            log.warn("[email:{}] rejected with {} for {}: {}",
                    PROVIDER, response.statusCode(), message.to(), truncate(response.body()));
            return Result.failed(PROVIDER, "SES returned " + response.statusCode());

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failed(PROVIDER, "interrupted");
        } catch (Exception failure) {
            // Contractually must not throw — see EmailPort.send.
            log.warn("[email:{}] failed for {}: {}", PROVIDER, message.to(), failure.toString());
            return Result.failed(PROVIDER, failure.getClass().getSimpleName());
        }
    }

    /** The SES v2 {@code SendEmail} payload. */
    private String buildRequest(Message message) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("FromEmailAddress", fromAddress);
        root.putObject("Destination").putArray("ToAddresses").add(message.to());

        ObjectNode content = root.putObject("Content").putObject("Simple");
        content.putObject("Subject")
                .put("Data", message.subject() == null ? "" : message.subject())
                .put("Charset", "UTF-8");

        ObjectNode bodyNode = content.putObject("Body");
        bodyNode.putObject("Text")
                .put("Data", message.textBody() == null ? "" : message.textBody())
                .put("Charset", "UTF-8");
        if (notBlank(message.htmlBody())) {
            bodyNode.putObject("Html").put("Data", message.htmlBody()).put("Charset", "UTF-8");
        }

        // Optional, and worth setting in production: a configuration set is what routes bounce and
        // complaint events back to you. Without it a hard bounce is invisible here, and repeated
        // sends to a dead address are what damage a sending reputation.
        if (notBlank(configurationSet)) {
            root.put("ConfigurationSetName", configurationSet);
        }

        return objectMapper.writeValueAsString(root);
    }

    private String readMessageId(String responseBody) {
        try {
            return objectMapper.readTree(responseBody).path("MessageId").asText("unknown");
        } catch (Exception unparseable) {
            return "unknown";
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }

    @Override
    public String provider() {
        return PROVIDER;
    }
}
