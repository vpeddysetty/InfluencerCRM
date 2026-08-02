package com.influencer.webe.workflow.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.config.WebExperienceProperties;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talks to the extracted Workflow service.
 *
 * <p>Deliberately a separate client from {@code DaoGatewayClient} rather than a reconfigured one:
 * the two targets have different base URLs, different credentials and — before long — different
 * availability characteristics. Sharing one client would mean a Workflow outage looked like a DAO
 * outage in every log line.
 */
@Component
public class WorkflowServiceClient {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final WebExperienceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WorkflowServiceClient(WebExperienceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // Plain HTTP to an internal service on the same network. Certificate verification for
        // the DAO exists because that hop is TLS; adding TLS here is a deployment concern, not a
        // reason to disable verification anywhere.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public JsonNode get(String path, Map<String, String> query) {
        return send(authorized(HttpRequest.newBuilder())
                .uri(buildUri(path, query))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build(), "GET", path);
    }

    public JsonNode post(String path, JsonNode payload) {
        return send(authorized(HttpRequest.newBuilder())
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build(), "POST", path);
    }

    public JsonNode put(String path, JsonNode payload) {
        return send(authorized(HttpRequest.newBuilder())
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build(), "PUT", path);
    }

    public JsonNode patch(String path, JsonNode payload) {
        return send(authorized(HttpRequest.newBuilder())
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build(), "PATCH", path);
    }

    public void delete(String path) {
        send(authorized(HttpRequest.newBuilder())
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(20))
                .DELETE()
                .build(), "DELETE", path);
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        String token = properties.getWorkflowServiceToken();
        if (token != null && !token.isBlank()) {
            builder.header(SERVICE_TOKEN_HEADER, token);
        }
        return builder;
    }

    private JsonNode send(HttpRequest request, String method, String path) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(mapStatus(response.statusCode()),
                        "Workflow service " + method + " " + path + " failed with status "
                                + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            // Names the Workflow service explicitly: during a cutover, "which service is down"
            // is the first question and the log should answer it.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to reach the Workflow service", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Workflow service call interrupted", exception);
        }
    }

    private HttpStatus mapStatus(int statusCode) {
        return switch (statusCode) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private URI buildUri(String path, Map<String, String> query) {
        StringBuilder builder = new StringBuilder(properties.getWorkflowServiceBaseUrl()).append(path);
        if (query != null && !query.isEmpty()) {
            Map<String, String> nonBlank = new LinkedHashMap<>();
            query.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    nonBlank.put(k, v);
                }
            });
            if (!nonBlank.isEmpty()) {
                builder.append("?");
                boolean first = true;
                for (Map.Entry<String, String> entry : nonBlank.entrySet()) {
                    if (!first) {
                        builder.append("&");
                    }
                    first = false;
                    builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                            .append("=")
                            .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                }
            }
        }
        return URI.create(builder.toString());
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize request", exception);
        }
    }
}
