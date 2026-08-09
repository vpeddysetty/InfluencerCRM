package com.influencer.webe.identity.infrastructure;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the mapping between an external provider account and a local user.
 *
 * <p>Follows {@link DaoUserClient} rather than the gateway clients: these are typed records with a
 * small fixed surface, not a passthrough of arbitrary JSON.
 */
@Component
public class DaoFederatedIdentityClient {
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final WebExperienceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DaoFederatedIdentityClient(WebExperienceProperties properties,
                                      ObjectMapper objectMapper,
                                      DaoHttpClientFactory httpClientFactory) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClientFactory.create();
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        String serviceToken = properties.getDaoServiceToken();
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.header(SERVICE_TOKEN_HEADER, serviceToken);
        }
        return builder;
    }

    /** Resolves a provider's subject id to the local user it authenticates. */
    public Optional<FederatedIdentityRecord> findBySubject(String provider, String subject) {
        if (provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        String uri = properties.getDaoBaseUrl() + "/federated-identities/by-subject"
                + "?provider=" + encode(provider)
                + "&subject=" + encode(subject);
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return sendOptional(request);
    }

    public List<FederatedIdentityRecord> findByUserId(UUID userId) {
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/federated-identities?userId=" + userId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DAO federated identity list failed with status " + response.statusCode());
            }
            return List.of(objectMapper.readValue(response.body(), FederatedIdentityRecord[].class));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to call DAO federated identities endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    /** Records that a provider authenticated this user, creating the link if it is new. */
    public FederatedIdentityRecord link(LinkPayload payload) {
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/federated-identities"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build();
        return sendOptional(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DAO did not return the linked federated identity"));
    }

    public void unlink(UUID id) {
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/federated-identities/" + id))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 404 && (response.statusCode() < 200 || response.statusCode() >= 300)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DAO federated identity unlink failed with status " + response.statusCode());
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to call DAO federated identities endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    private Optional<FederatedIdentityRecord> sendOptional(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DAO federated identity request failed with status " + response.statusCode() + ": " + response.body());
            }
            return Optional.of(objectMapper.readValue(response.body(), FederatedIdentityRecord.class));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to call DAO federated identities endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize request", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record FederatedIdentityRecord(
            UUID id,
            UUID userId,
            String provider,
            String subject,
            String assertedEmail,
            boolean emailVerifiedByIdp,
            Instant linkedAt,
            Instant lastAuthenticatedAt) {
    }

    public record LinkPayload(
            UUID userId,
            String provider,
            String subject,
            String assertedEmail,
            boolean emailVerifiedByIdp) {
    }
}
