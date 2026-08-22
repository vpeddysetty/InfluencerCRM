package com.influencer.webe.identity.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.config.WebExperienceProperties;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes proof-of-address challenges.
 *
 * <p>Follows {@link DaoFederatedIdentityClient}: typed records over a small fixed surface rather
 * than a passthrough of arbitrary JSON.
 */
@Component
public class DaoEmailVerificationClient {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebExperienceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DaoEmailVerificationClient(WebExperienceProperties properties,
                                      ObjectMapper objectMapper,
                                      DaoHttpClientFactory httpClientFactory) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClientFactory.create();
    }

    /**
     * Whether this account is locked pending verification.
     *
     * <p><b>Fails CLOSED on a DAO error</b>, unlike most reads here. This answers the sign-in gate,
     * and treating an unreachable DAO as "nothing pending" would turn a transient outage into a
     * bypass of the check — exactly the failure the gate exists to prevent. A sign-in refused
     * during an outage is recoverable; one wrongly allowed is not.
     */
    public boolean hasPendingVerification(UUID userId) {
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/email-verifications/pending?userId=" + userId))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return true;
            }
            return objectMapper.readValue(response.body(), PendingResponse.class).pending();
        } catch (IOException exception) {
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    public Optional<VerificationRecord> findByToken(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl()
                        + "/email-verifications/by-token?tokenHash=" + encode(tokenHash)))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return sendOptional(request);
    }

    public Optional<VerificationRecord> findCurrent(UUID userId) {
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/email-verifications?userId=" + userId))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return sendOptional(request);
    }

    public VerificationRecord create(UUID userId, String email, String tokenHash, Instant expiresAt) {
        return post("/email-verifications",
                new CreateRequest(userId, email, tokenHash, expiresAt));
    }

    /** Marks the challenge used and the user proven, in one DAO transaction. */
    public VerificationRecord consume(UUID verificationId) {
        return post("/email-verifications/" + verificationId + "/consume", null);
    }

    /** Records another send, rotating the token so the previous link stops working. */
    public VerificationRecord recordSend(UUID verificationId, String tokenHash, Instant expiresAt) {
        return post("/email-verifications/" + verificationId + "/sent",
                new RecordSendRequest(tokenHash, expiresAt));
    }

    /** Stamps a user proven with no challenge — federated signups only. */
    public void markVerified(UUID userId) {
        post("/email-verifications/mark-verified", new MarkVerifiedRequest(userId));
    }

    private VerificationRecord post(String path, Object body) {
        HttpRequest.Builder builder = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json");
        builder = body == null
                ? builder.POST(HttpRequest.BodyPublishers.noBody())
                : builder.POST(HttpRequest.BodyPublishers.ofString(writeJson(body)));
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification already used");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DAO email verification call failed with status " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                return null;
            }
            return objectMapper.readValue(response.body(), VerificationRecord.class);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to call DAO email verification endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    private Optional<VerificationRecord> sendOptional(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DAO email verification lookup failed with status " + response.statusCode());
            }
            return Optional.of(objectMapper.readValue(response.body(), VerificationRecord.class));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to call DAO email verification endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        String serviceToken = properties.getDaoServiceToken();
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.header(SERVICE_TOKEN_HEADER, serviceToken);
        }
        return builder;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to serialise email verification payload", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerificationRecord(UUID id, UUID userId, String email, String tokenHash,
                                     Instant expiresAt, Instant consumedAt, int sendCount,
                                     Instant lastSentAt, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PendingResponse(boolean pending) {
    }

    private record CreateRequest(UUID userId, String email, String tokenHash, Instant expiresAt) {
    }

    private record RecordSendRequest(String tokenHash, Instant expiresAt) {
    }

    private record MarkVerifiedRequest(UUID userId) {
    }
}
