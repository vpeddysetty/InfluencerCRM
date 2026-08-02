package com.influencer.campaign.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.campaign.ports.CreatorProvisioningPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Satisfies {@link CreatorProvisioningPort} over HTTP against the Creator service.
 *
 * <p>Spreadsheet import creates creators, which the Creator context owns. In the monolith that was
 * an in-process call; now it is a network hop, and the interface is unchanged — the importer did not
 * have to be rewritten, which is exactly what the port was introduced for.
 *
 * <p>Failures here are <em>not</em> swallowed, unlike brand lookup. A creator that silently fails to
 * be created would leave the import reporting success while producing nothing, and the operator
 * would discover it only when the data was missing. Import is a long, expensive, user-initiated
 * operation: failing loudly mid-run is far kinder than a quietly partial result.
 */
@Component
public class CreatorProvisioningHttpAdapter implements CreatorProvisioningPort {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final String creatorBaseUrl;
    private final String serviceToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CreatorProvisioningHttpAdapter(
            @Value("${campaign.creator-service-url:http://localhost:8446}") String creatorBaseUrl,
            @Value("${campaign.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.creatorBaseUrl = creatorBaseUrl;
        this.serviceToken = serviceToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public ProvisionResult findOrCreateCreator(UUID brandId,
                                               UUID importBatchId,
                                               String defaultSource,
                                               Map<String, Object> attributes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brandId", brandId);
        body.put("importBatchId", importBatchId);
        body.put("defaultSource", defaultSource);
        body.put("attributes", attributes);

        JsonNode response = post("/creator-provisioning/creators", body);
        return new ProvisionResult(
                UUID.fromString(response.get("id").asText()),
                response.get("created").asBoolean());
    }

    @Override
    public Optional<UUID> findCreatorId(UUID brandId, String platform, String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String query = "?brandId=" + brandId
                + "&platform=" + encode(platform == null || platform.isBlank() ? "instagram" : platform)
                + "&handle=" + encode(handle);
        JsonNode response = get("/creator-provisioning/creators/lookup" + query);
        return response.hasNonNull("creatorId")
                ? Optional.of(UUID.fromString(response.get("creatorId").asText()))
                : Optional.empty();
    }

    @Override
    public boolean creatorExists(UUID creatorId) {
        if (creatorId == null) {
            return false;
        }
        return get("/creator-provisioning/creators/" + creatorId + "/exists").get("exists").asBoolean();
    }

    @Override
    public ProvisionResult linkCreatorToCampaign(UUID brandId,
                                                 UUID importBatchId,
                                                 UUID campaignId,
                                                 UUID creatorId,
                                                 Map<String, Object> attributes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brandId", brandId);
        body.put("importBatchId", importBatchId);
        body.put("campaignId", campaignId);
        body.put("creatorId", creatorId);
        body.put("attributes", attributes);

        JsonNode response = post("/creator-provisioning/campaign-creators", body);
        return new ProvisionResult(
                UUID.fromString(response.get("id").asText()),
                response.get("created").asBoolean());
    }

    @Override
    public boolean isLinkedToCampaign(UUID campaignId, UUID creatorId) {
        String query = "?campaignId=" + campaignId + "&creatorId=" + creatorId;
        return get("/creator-provisioning/campaign-creators/exists" + query).get("exists").asBoolean();
    }

    private JsonNode get(String path) {
        return send(authorized(HttpRequest.newBuilder().uri(uri(path)).GET()).build(), "GET", path);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return send(authorized(HttpRequest.newBuilder()
                .uri(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))).build(), "POST", path);
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        builder.timeout(Duration.ofSeconds(20));
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.header(SERVICE_TOKEN_HEADER, serviceToken);
        }
        return builder;
    }

    private JsonNode send(HttpRequest request, String method, String path) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Creator service " + method + " " + path + " failed with status "
                                + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Creator service call interrupted", exception);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            // Names the Creator service explicitly: mid-import, "which service failed" is the
            // first question and the message should answer it.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to reach the Creator service", exception);
        }
    }

    private URI uri(String path) {
        return URI.create(creatorBaseUrl + path);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize request", exception);
        }
    }
}
