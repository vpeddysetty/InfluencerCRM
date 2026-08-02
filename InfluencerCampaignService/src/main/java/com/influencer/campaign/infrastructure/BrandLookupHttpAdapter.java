package com.influencer.campaign.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.campaign.ports.BrandLookupPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Satisfies {@link BrandLookupPort} over HTTP against the Identity service.
 *
 * <p>This is the shape every cross-context dependency takes after extraction: the interface is
 * unchanged, so nothing that <em>uses</em> the port had to be touched — only what stands behind it.
 * That property is the whole reason the ports were introduced before the split.
 *
 * <p>Brand existence is also enforced by a surviving foreign key to {@code identity.brands}, so this
 * check is a fast-fail with a clear message rather than the only line of defence. That is why a
 * lookup failure is treated as "unknown" rather than escalated: the database still refuses a bad
 * write, and a transient Identity outage should not block work whose integrity is guaranteed
 * elsewhere.
 */
@Component
public class BrandLookupHttpAdapter implements BrandLookupPort {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final String identityBaseUrl;
    private final String serviceToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public BrandLookupHttpAdapter(
            @Value("${campaign.identity-service-url:http://localhost:8445}") String identityBaseUrl,
            @Value("${campaign.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.identityBaseUrl = identityBaseUrl;
        this.serviceToken = serviceToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public boolean brandExists(UUID brandId) {
        return brandId != null && fetchBrand(brandId).isPresent();
    }

    @Override
    public Optional<String> brandName(UUID brandId) {
        return fetchBrand(brandId)
                .map(node -> node.hasNonNull("name") ? node.get("name").asText() : null);
    }

    private Optional<JsonNode> fetchBrand(UUID brandId) {
        if (brandId == null) {
            return Optional.empty();
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(identityBaseUrl + "/tenancy/brands/" + brandId))
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            if (serviceToken != null && !serviceToken.isBlank()) {
                builder.header(SERVICE_TOKEN_HEADER, serviceToken);
            }

            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception exception) {
            // Unknown rather than fatal — see the class comment: the FK to identity.brands is
            // still the guarantee, so a transient Identity outage must not block the write.
            return Optional.empty();
        }
    }
}
