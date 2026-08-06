package com.influencer.webe.creator.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls agent_service to classify a creator (roadmap C.3).
 *
 * <p><b>Why this exists rather than reusing the campaign context's agent client.</b> Both call
 * the same service, so sharing looked reasonable — but the BFF's ArchUnit boundary test
 * forbids one context depending on another's {@code infrastructure} package, and it is right
 * to. An outbound client is a context's own detail; reaching across for it re-couples the two
 * contexts through a class neither owns, and the next change to campaign's client would then
 * silently affect creator onboarding. Two small clients against one HTTP endpoint is the
 * cheaper arrangement.
 *
 * <p><b>Never throws.</b> Classification is enrichment: a creator signing up must not be
 * blocked because a classifier was unavailable (C.6). A null return means "unclassified", and
 * the caller records the lead with {@code classification_source} unset — visibly unclassified
 * rather than silently mislabelled.
 */
@Component
public class CreatorClassificationClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final WebExperienceProperties properties;
    private final ObjectMapper objectMapper;

    public CreatorClassificationClient(WebExperienceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * @return the {@code classification} object, or null when the agent is unreachable, slow,
     *         or answered with anything other than success
     */
    public JsonNode classify(JsonNode payload) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getAgentBaseUrl() + "/creators/classify"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
        } catch (Exception e) {
            return null;
        }

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                return null;
            }
            JsonNode parsed = objectMapper.readTree(response.body());
            return parsed.get("classification");
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
