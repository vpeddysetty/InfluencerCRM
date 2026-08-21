package com.influencer.dps.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.dps.config.DpsProperties;
import com.influencer.platform.workload.WorkloadToken;
import com.influencer.platform.workload.WorkloadTokenIssuer;
import org.slf4j.MDC;
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
import java.util.Set;

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
    private final WorkloadTokenIssuer workloadTokens;

    public IdentityClient(DpsProperties properties,
                          ObjectMapper objectMapper,
                          WorkloadTokenIssuer workloadTokens) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.workloadTokens = workloadTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public JsonNode login(String email, String password) {
        return post("/api/auth/login", Map.of("email", email, "password", password), null);
    }

    public JsonNode signup(String email, String password, String brandName, String accountType) {
        return signup(email, password, brandName, accountType, null, null, null);
    }

    /**
     * Signs a new account up, carrying the consent given on the form.
     *
     * <p>{@code acceptedTerms} was not forwarded until 2026-08-14, which broke email-and-password
     * signup through the DPS: the BFF requires consent and never received any, so every attempt was
     * refused with "You must accept the Terms of Service and Privacy Policy to continue".
     *
     * <p>The IP and user agent are forwarded too, and matter more than they look. The BFF reads the
     * client address from {@code X-Forwarded-For} and records it against the consent; without these
     * headers this call is just another server-to-server request, so every consent record would
     * attest that the DPS container agreed to the terms. A consent record naming the wrong client is
     * worse than an absent one — it is evidence that says something untrue.
     */
    public JsonNode signup(String email,
                           String password,
                           String brandName,
                           String accountType,
                           Boolean acceptedTerms,
                           String clientIp,
                           String userAgent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("brandName", brandName);
        // Omitted rather than sent as null when absent: the BFF defaults it, and the signup
        // payload now rejects unknown/unusable properties rather than ignoring them.
        if (accountType != null && !accountType.isBlank()) {
            body.put("accountType", accountType);
        }
        if (acceptedTerms != null) {
            body.put("acceptedTerms", acceptedTerms);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (clientIp != null && !clientIp.isBlank()) {
            headers.put("X-Forwarded-For", clientIp);
        }
        if (userAgent != null && !userAgent.isBlank()) {
            headers.put("User-Agent", userAgent);
        }
        return post("/api/auth/signup", body, null, headers);
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
     * Asks the BFF where to send a browser to connect a provider to this session's account.
     *
     * <p>The URL comes back as data and the DPS issues the redirect itself, so the access token
     * travels on this call — server to server — rather than in a browser-visible query string. A
     * bearer token in a URL ends up in history, in the Referer sent to the provider, and in every
     * access log between here and there.
     */
    public String authorizationUrlForLink(String provider, String accessToken) {
        JsonNode response = get("/api/auth/connected-accounts/" + provider + "/start", accessToken, null);
        JsonNode url = response == null ? null : response.get("authorizationUrl");
        if (url == null || url.isNull() || url.asText().isBlank()) {
            throw new IllegalStateException("BFF returned no authorization URL for " + provider);
        }
        return url.asText();
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
            // Step 4: the user's bearer says WHO the request is for; this says WHICH SERVICE is
            // asking. Without it the BFF cannot distinguish a call relayed by the DPS from one
            // made by anything else holding a valid user token, so "the DPS vouched for this"
            // is not a fact it can check.
            String workload = workloadTokens.issueFor("bff", Set.of("bff:proxy"), brandId);
            if (workload != null) {
                builder.header(WorkloadToken.HEADER, workload);
            }
            // Correlation: one browser action keeps one id from here through the BFF to the DAO.
            String requestId = MDC.get("rid");
            if (requestId != null && !requestId.isBlank()) {
                builder.header("X-Request-Id", requestId);
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
        return send("POST", path, body, accessToken, Map.of());
    }

    /** As above, forwarding headers that describe the ORIGINAL caller rather than this service. */
    private JsonNode post(String path, Map<String, Object> body, String accessToken, Map<String, String> headers) {
        return send("POST", path, body, accessToken, headers);
    }

    private JsonNode get(String path, String accessToken, Map<String, Object> ignored) {
        return send("GET", path, null, accessToken, Map.of());
    }

    private JsonNode send(String method,
                          String path,
                          Map<String, Object> body,
                          String accessToken,
                          Map<String, String> extraHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBffBaseUrl() + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json");

            // Allow-listed, not passed through. HttpRequest.Builder#header APPENDS rather than
            // replaces, so forwarding arbitrary names would let a caller add a second Authorization
            // or service-token header alongside the real one — and which of the two an upstream
            // honours is not a question worth leaving open. These two describe the original client
            // and are the only ones this needs to carry.
            extraHeaders.forEach((name, value) -> {
                if ("X-Forwarded-For".equalsIgnoreCase(name) || "User-Agent".equalsIgnoreCase(name)) {
                    builder.header(name, value);
                }
            });

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
