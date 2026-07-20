package com.influencer.webe.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@Component
public class DaoUserClient {
    private final WebExperienceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DaoUserClient(WebExperienceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = buildHttpClient();
    }

    public List<UserRecord> listUsers() {
        HttpRequest request = HttpRequest.newBuilder()
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getDaoBaseUrl() + "/users/by-email?email=" + normalizedEmail))
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
        return sendUser(HttpRequest.newBuilder()
                .uri(URI.create(properties.getDaoBaseUrl() + "/users"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build());
    }

    public UserRecord updateUser(UUID id, UserPayload payload) {
        return sendUser(HttpRequest.newBuilder()
                .uri(URI.create(properties.getDaoBaseUrl() + "/users/" + id))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build());
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

    private HttpClient buildHttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create HTTP client", exception);
        }
    }

    public record UserRecord(UUID id, String email, String passwordHash, String brandName, String customAttributes, String role, String plan, Instant createdAt, Instant updatedAt) {
    }

    public record UserPayload(UUID id, String email, String passwordHash, String brandName, String customAttributes, String role, String plan, Instant createdAt, Instant updatedAt) {
    }
}
