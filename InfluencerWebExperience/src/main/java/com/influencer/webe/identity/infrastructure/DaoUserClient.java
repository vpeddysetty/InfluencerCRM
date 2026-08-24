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

@Component
public class DaoUserClient {
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final WebExperienceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DaoUserClient(WebExperienceProperties properties, ObjectMapper objectMapper, DaoHttpClientFactory httpClientFactory) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClientFactory.create();
    }

    /** Stamps the service credential the DAO requires on every outbound call. */
    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        String serviceToken = properties.getDaoServiceToken();
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.header(SERVICE_TOKEN_HEADER, serviceToken);
        }
        return builder;
    }

    public List<UserRecord> listUsers() {
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/users"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return executeList(request);
    }

    public Optional<UserRecord> findByEmail(String email) {
        String normalizedEmail = email == null ? null : email.trim().toLowerCase();
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return Optional.empty();
        }
        HttpRequest request = authorized(HttpRequest.newBuilder())
                // ENCODED. An address is user-supplied and routinely contains "+", which a query
                // string decodes to a SPACE - so "a+b@x.com" was looked up as "a b@x.com", found
                // nothing, and login answered "Invalid credentials" to someone typing the right
                // password. Plus-addressing is the normal way people tag a signup, so this hit real
                // users, not just tests. Found 2026-08-22.
                .uri(URI.create(properties.getDaoBaseUrl() + "/users/by-email?email="
                        + URLEncoder.encode(normalizedEmail, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO user lookup failed with status " + response.statusCode() + ": " + response.body());
            }
            return Optional.of(objectMapper.readValue(response.body(), UserRecord.class));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to call DAO users endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    public Optional<UserRecord> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        HttpRequest request = authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/users/" + id))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO user lookup failed with status " + response.statusCode() + ": " + response.body());
            }
            return Optional.of(objectMapper.readValue(response.body(), UserRecord.class));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to call DAO users endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    public UserRecord createUser(UserPayload payload) {
        return sendUser(authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/users"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build());
    }

    public UserRecord updateUser(UUID id, UserPayload payload) {
        return sendUser(authorized(HttpRequest.newBuilder())
                .uri(URI.create(properties.getDaoBaseUrl() + "/users/" + id))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build());
    }

    /**
     * Deletes a user and everything the schema cascades from it.
     *
     * <p>Used only by the deletion workflow, and only after a human has approved. Consent records
     * and the deletion request itself deliberately survive -- their foreign keys are SET NULL or
     * absent, because they are the evidence the deletion was lawful.
     *
     * <p>A 404 is treated as success: the goal is that the account no longer exists, and one that
     * was already gone satisfies that. Throwing would make a retry of an interrupted purge look
     * like a failure and invite someone to investigate a problem that is not there.
     */
    public void deleteUser(UUID id) {
        try {
            HttpResponse<String> response = httpClient.send(
                    authorized(HttpRequest.newBuilder())
                            .uri(URI.create(properties.getDaoBaseUrl() + "/users/" + id))
                            .timeout(Duration.ofSeconds(30))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404 || (status >= 200 && status < 300)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "DAO refused to delete user " + id + ": " + status + " " + response.body());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to call DAO to delete a user", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    /**
     * Removes one federated identity, leaving the account intact.
     *
     * <p>The provider-scoped deletion {@code /data-deletion/} section 3.2 promises separately, and
     * which Meta's reviewers test: delete what came from Facebook without deleting the account.
     */
    public void deleteFederatedIdentity(UUID userId, String provider) {
        try {
            HttpResponse<String> response = httpClient.send(
                    authorized(HttpRequest.newBuilder())
                            .uri(URI.create(properties.getDaoBaseUrl()
                                    + "/federated-identities/users/" + userId + "?provider="
                                    + URLEncoder.encode(provider, StandardCharsets.UTF_8)))
                            .timeout(Duration.ofSeconds(20))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404 || (status >= 200 && status < 300)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "DAO refused to delete the " + provider + " identity: " + status);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to call DAO to delete a federated identity", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    /**
     * Whether this user owns a workspace, which makes deleting them somebody else's problem too.
     *
     * <p>An owner's workspace holds creator records the BRAND is controller for. Deleting the owner
     * would erase other people's personal data held under the brand's legal basis, and remove
     * teammates' access without notice -- so the deletion workflow refuses and asks for ownership
     * to be transferred first.
     *
     * <p>Ownership is a membership row with {@code role = 'OWNER'}, which is why this asks the
     * tenancy endpoint rather than looking for an owner column that does not exist.
     */
    public boolean ownsAnyBrand(UUID userId) {
        try {
            HttpResponse<String> response = httpClient.send(
                    authorized(HttpRequest.newBuilder())
                            .uri(URI.create(properties.getDaoBaseUrl()
                                    + "/tenancy/users/" + userId + "/owned-brands"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return false;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DAO ownership check failed with status " + response.statusCode());
            }
            com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(response.body());
            return body != null && body.isArray() && !body.isEmpty();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to check workspace ownership", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    private UserRecord sendUser(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO user request failed with status " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), UserRecord.class);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to call DAO users endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    private List<UserRecord> executeList(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO user list request failed with status " + response.statusCode());
            }
            return List.of(objectMapper.readValue(response.body(), UserRecord[].class));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to read DAO users response", exception);
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

    public record UserRecord(UUID id, String email, String passwordHash, String brandName, String customAttributes, String role, String plan, Instant createdAt, Instant updatedAt) {
    }

    public record UserPayload(UUID id, String email, String passwordHash, String brandName, String customAttributes, String role, String plan, Instant createdAt, Instant updatedAt) {
    }
}
