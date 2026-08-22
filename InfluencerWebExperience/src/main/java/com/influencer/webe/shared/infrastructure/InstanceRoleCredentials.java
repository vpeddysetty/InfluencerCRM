package com.influencer.webe.shared.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Temporary AWS credentials from the EC2 instance role, via IMDSv2.
 *
 * <p><b>Why this exists.</b> The instance role already grants {@code ses:SendEmail}, but
 * {@code SesEmailSender} only accepted static keys — so sending mail from the deployed platform
 * would have meant creating a long-lived IAM user and putting its secret in configuration. That is
 * a worse credential than the one already attached to the machine: it does not rotate, it works
 * from anywhere, and it has to be stored somewhere. These expire on their own and never leave the
 * instance.
 *
 * <p><b>IMDSv2 only.</b> The token-based flow is required on this fleet and is the one that resists
 * SSRF: an attacker who tricks the app into fetching a URL cannot read credentials without also
 * being able to send a PUT with a custom header and use the response.
 *
 * <p><b>Absent off EC2.</b> On a laptop or in a test there is no metadata service, so
 * {@link #current()} returns null quickly and the caller falls back to static keys. The short
 * timeouts matter: a hang here would stall every send rather than failing one.
 */
public final class InstanceRoleCredentials {

    private static final Logger log = LoggerFactory.getLogger(InstanceRoleCredentials.class);

    private static final String IMDS = "http://169.254.169.254";
    private static final String TOKEN_PATH = "/latest/api/token";
    private static final String ROLE_PATH = "/latest/meta-data/iam/security-credentials/";

    /** Deliberately short. Off EC2 this address is unroutable and must fail fast, not hang. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** Re-fetch this long before expiry, so a send never uses a credential that dies mid-flight. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile Credentials cached;

    public InstanceRoleCredentials(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** Live credentials, or null when not running on EC2 or the role cannot be read. */
    public Credentials current() {
        Credentials existing = cached;
        if (existing != null && existing.usableAt(Instant.now())) {
            return existing;
        }
        Credentials fetched = fetch();
        if (fetched != null) {
            cached = fetched;
        }
        return fetched;
    }

    private Credentials fetch() {
        try {
            String token = imdsToken();
            if (token == null) {
                return null;
            }
            String roleName = get(ROLE_PATH, token);
            if (roleName == null || roleName.isBlank()) {
                return null;
            }
            // The listing can carry more than one line; the first is the attached role.
            String role = roleName.strip().lines().findFirst().orElse("").strip();
            String body = get(ROLE_PATH + role, token);
            if (body == null) {
                return null;
            }
            JsonNode json = objectMapper.readTree(body);
            String accessKey = json.path("AccessKeyId").asText(null);
            String secret = json.path("SecretAccessKey").asText(null);
            String session = json.path("Token").asText(null);
            String expiry = json.path("Expiration").asText(null);
            if (accessKey == null || secret == null || session == null) {
                return null;
            }
            Instant expiresAt = expiry == null ? Instant.now().plusSeconds(900) : Instant.parse(expiry);
            log.debug("[aws] assumed instance role {}, credentials expire {}", role, expiresAt);
            return new Credentials(accessKey, secret, session, expiresAt);
        } catch (Exception exception) {
            // Any failure means "no instance credentials", which is a normal state off EC2 rather
            // than an error worth a stack trace on every send.
            log.debug("[aws] no instance-role credentials available: {}", exception.toString());
            return null;
        }
    }

    private String imdsToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMDS + TOKEN_PATH))
                .timeout(TIMEOUT)
                .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }

    private String get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMDS + path))
                .timeout(TIMEOUT)
                .header("X-aws-ec2-metadata-token", token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }

    /** A temporary credential set. */
    public record Credentials(String accessKeyId, String secretAccessKey, String sessionToken,
                              Instant expiresAt) {

        /** Whether this is still good, with margin — see {@link #REFRESH_MARGIN}. */
        public boolean usableAt(Instant now) {
            return expiresAt != null && expiresAt.minus(REFRESH_MARGIN).isAfter(now);
        }
    }
}
