package com.influencer.dps.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.dps.config.DpsProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Brokers authentication and API calls to the BFF, which fronts the Identity context.
 *
 * <p>The DPS deliberately does not talk to Identity directly. The BFF already owns token issuance,
 * brand-access resolution and the permission matrix; duplicating any of that here would create two
 * implementations of authorization that could disagree — the exact failure that made FINANCE users
 * unable to log in when one rule was written twice.
 *
 * <p>What the DPS adds is the <em>browser</em> half: turning tokens into an httpOnly cookie session
 * so no credential reaches JavaScript.
 */
@Component
public class IdentityClient {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final DpsProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public IdentityClient(DpsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public JsonNode login(String email, String password) {
        return post("/api/auth/login", Map.of("email", email, "password", password), null);
    }

    public JsonNode signup(String email, String password, String brandName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("brandName", brandName);
        return post("/api/auth/signup", body, null);
    }

    public JsonNode refresh(String refreshToken) {
        return post("/api/auth/refresh", Map.of("refreshToken", refreshToken), null);
    }

    /**
     * Redeems a single-use OAuth handoff code for the completed sign-in.
     *
     * <p>This is the server-to-server half of the OAuth flow. The browser is redirected to the DPS
     * carrying only the opaque code; the tokens travel on this call, which never touches the
     * browser. That is what keeps a federated sign-in as token-free, from JavaScript's point of
     * view, as a password one.
     */
    public JsonNode redeemOAuthHandoff(String handoffCode) {
        return post("/api/auth/oauth/handoff", Map.of("handoff", handoffCode), null);
    }

    public void logout(String refreshToken) {
        try {
            post("/api/auth/logout", Map.of("refreshToken", refreshToken), null);
        } catch (Exception exception) {
            // The end state the caller wants — no live session — is reached locally regardless.
            // Failing logout because the token was already gone would be actively unhelpful.
        }
    }

    public JsonNode listBrands(String accessToken) {
        return get("/api/brands", accessToken, null);
    }

    public JsonNode switchBrand(String accessToken, String brandId) {
        return post("/api/brands/switch", Map.of("brandId", brandId), accessToken);
    }

    /**
     * Proxies an arbitrary API call on a session's behalf.
     *
     * <p>This is what lets a remote reach the platform without ever holding a token: it calls the
     * DPS with a cookie, and the DPS attaches the bearer token here.
     */
    public HttpResponse<String> proxy(String method,
                                      String path,
                                      String accessToken,
                                      String brandId,
                                      String body,
                                      String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBffBaseUrl() + path))
                    .timeout(Duration.ofSeconds(30));

            if (accessToken != null && !accessToken.isBlank()) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            // The tenancy key is stamped by the DPS, so no caller can pick a different brand.
            if (brandId != null && !brandId.isBlank()) {
                builder.header("X-Brand-Id", brandId);
            }
            if (contentType != null && !contentType.isBlank()) {
                builder.header("Content-Type", contentType);
            }

            HttpRequest.BodyPublisher publisher = (body == null || body.isBlank())
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            builder.method(method, publisher);

            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream call interrupted", exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to reach the platform API", exception);
        }
    }

    private JsonNode post(String path, Map<String, Object> body, String accessToken) {
        return send("POST", path, body, accessToken);
    }

    private JsonNode get(String path, String accessToken, Map<String, Object> ignored) {
        return send("GET", path, null, accessToken);
    }

    private JsonNode send(String method, String path, Map<String, Object> body, String accessToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBffBaseUrl() + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json");

            if (accessToken != null && !accessToken.isBlank()) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            if (properties.getServiceToken() != null && !properties.getServiceToken().isBlank()) {
                builder.header(SERVICE_TOKEN_HEADER, properties.getServiceToken());
            }

            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Upstream status is preserved: a bad password must reach the browser as 400, not
                // as a 502 that reads like the service is broken.
                throw new ResponseStatusException(
                        HttpStatus.valueOf(response.statusCode()),
                        extractMessage(response.body()));
            }
            return response.body() == null || response.body().isBlank()
                    ? objectMapper.nullNode()
                    : objectMapper.readTree(response.body());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream call interrupted", exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to reach the platform API", exception);
        }
    }

    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "Upstream request failed";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        return body;
    }
}
