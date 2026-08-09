package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.config.WebExperienceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class OAuthProfileService {
    private static final Logger log = LoggerFactory.getLogger(OAuthProfileService.class);
    private final WebExperienceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OAuthProfileService(WebExperienceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = buildHttpClient();
    }

    public OAuthProfile resolveProfile(String provider, String accessToken, String fallbackEmail, String fallbackDisplayName) {
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                return switch (provider.toLowerCase()) {
                    case "google" -> fetchGoogleProfile(accessToken);
                    case "facebook" -> fetchFacebookProfile(accessToken);
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
                };
            } catch (Exception exception) {
                log.warn("Failed to resolve {} profile from access token", provider, exception);
                if (fallbackEmail == null || fallbackEmail.isBlank()) {
                    // Surface the specific reason (e.g. "profile did not include an email address",
                    // "profile lookup failed with status 401") rather than a generic message.
                    if (exception instanceof ResponseStatusException rse) {
                        throw rse;
                    }
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unable to resolve social profile: " + exception.getMessage(), exception);
                }
            }
        }

        if (fallbackEmail == null || fallbackEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email or accessToken is required");
        }

        return new OAuthProfile(
                provider.toLowerCase(),
                "local-" + provider.toLowerCase() + "-" + fallbackEmail.toLowerCase(),
                fallbackEmail.trim().toLowerCase(),
                fallbackDisplayName == null || fallbackDisplayName.isBlank() ? fallbackEmail.trim() : fallbackDisplayName.trim(),
                // Never verified: this address came from the caller, not from a provider. Treating
                // a self-asserted email as verified would let anyone claim an existing account by
                // naming its address — the exact takeover this flag exists to prevent.
                false,
                "{}");
    }

    public String exchangeAuthorizationCode(String provider, String code, String redirectUri) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is required");
        }

        try {
            return switch (provider.toLowerCase()) {
                case "google" -> exchangeGoogleAuthorizationCode(code, redirectUri);
                case "facebook" -> exchangeFacebookAuthorizationCode(code, redirectUri);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
            };
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, provider + " authorization-code exchange failed", exception);
        }
    }

    private OAuthProfile fetchGoogleProfile(String accessToken) throws Exception {
        return fetchProfile(
                "google",
                properties.getOauth().getGoogle().getUserinfoUri(),
                accessToken,
                node -> new OAuthProfile(
                        "google",
                        text(node, "sub", "google-unknown"),
                        text(node, "email", null),
                        text(node, "name", text(node, "email", "Google User")),
                        // Google returns email_verified on the userinfo endpoint. Defaulting to
                        // false when absent is deliberate: a missing claim is not evidence of
                        // verification, and this flag gates account linking.
                        node.path("email_verified").asBoolean(false),
                        node.toString()));
    }

    private OAuthProfile fetchFacebookProfile(String accessToken) throws Exception {
        return fetchProfile(
                "facebook",
                properties.getOauth().getFacebook().getUserinfoUri(),
                accessToken,
                node -> new OAuthProfile(
                        "facebook",
                        text(node, "id", "facebook-unknown"),
                        text(node, "email", null),
                        text(node, "name", text(node, "email", "Facebook User")),
                        // Facebook's Graph API exposes no per-address verification claim, so an
                        // email from it can never satisfy the auto-link check. Linking a Facebook
                        // identity to an existing account stays an explicit, signed-in action.
                        false,
                        node.toString()));
    }

    private String exchangeGoogleAuthorizationCode(String code, String redirectUri) throws Exception {
        WebExperienceProperties.Google google = properties.getOauth().getGoogle();
        requireConfigured(google.getTokenUri(), "google.token-uri");
        requireConfigured(google.getClientId(), "google.client-id");
        requireConfigured(google.getClientSecret(), "google.client-secret");
        requireConfigured(redirectUri, "google.redirect-uri");

        Map<String, String> form = new HashMap<>();
        form.put("code", code);
        form.put("client_id", google.getClientId());
        form.put("client_secret", google.getClientSecret());
        form.put("redirect_uri", redirectUri);
        form.put("grant_type", "authorization_code");

        return exchangeToken(google.getTokenUri(), form, "access_token");
    }

    private String exchangeFacebookAuthorizationCode(String code, String redirectUri) throws Exception {
        WebExperienceProperties.Facebook facebook = properties.getOauth().getFacebook();
        requireConfigured(facebook.getTokenUri(), "facebook.token-uri");
        requireConfigured(facebook.getClientId(), "facebook.client-id");
        requireConfigured(facebook.getClientSecret(), "facebook.client-secret");
        requireConfigured(redirectUri, "facebook.redirect-uri");

        Map<String, String> form = new HashMap<>();
        form.put("code", code);
        form.put("client_id", facebook.getClientId());
        form.put("client_secret", facebook.getClientSecret());
        form.put("redirect_uri", redirectUri);

        return exchangeToken(facebook.getTokenUri(), form, "access_token");
    }

    private String exchangeToken(String tokenUri, Map<String, String> form, String tokenFieldName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(asFormBody(form)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Token exchange failed with status " + response.statusCode() + ": " + response.body());
        }

        String body = response.body();
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception ignored) {
            node = parseQueryString(body);
        }

        String accessToken = text(node, tokenFieldName, null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Token exchange did not return an access token");
        }
        return accessToken;
    }

    private JsonNode parseQueryString(String body) throws Exception {
        JsonNode root = objectMapper.createObjectNode();
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = pair.substring(0, index);
            String value = pair.substring(index + 1);
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put(key, value);
        }
        return root;
    }

    private String asFormBody(Map<String, String> form) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private String requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank() || "replace-me".equalsIgnoreCase(value.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, propertyName + " is not configured");
        }
        return value.trim();
    }

    private OAuthProfile fetchProfile(String provider, String uriTemplate, String accessToken, ProfileMapper mapper) throws Exception {
        if (uriTemplate == null || uriTemplate.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, provider + " userinfo uri is not configured");
        }

        String uri = provider.equals("facebook") && !uriTemplate.contains("access_token=")
                ? uriTemplate + "&access_token=" + accessToken
                : uriTemplate;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET();

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, provider + " profile lookup failed with status " + response.statusCode());
        }

        JsonNode node = objectMapper.readTree(response.body());
        String email = text(node, "email", null);
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, provider + " profile did not include an email address");
        }
        return mapper.map(node);
    }

    private String text(JsonNode node, String fieldName, String defaultValue) {
        JsonNode field = node.get(fieldName);
        return field != null && field.isTextual() && !field.asText().isBlank() ? field.asText() : defaultValue;
    }

    /**
     * Builds the client used to call Google's and Facebook's token and userinfo endpoints.
     *
     * <p>This previously installed a trust-all {@code X509TrustManager}, disabling certificate
     * verification on requests that carry OAuth client secrets and access tokens over the public
     * internet. Those providers present ordinary CA-issued certificates, so the JVM's default trust
     * store is both correct and sufficient — there is no self-signed case to accommodate here.
     */
    private HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @FunctionalInterface
    private interface ProfileMapper {
        OAuthProfile map(JsonNode node);
    }

    /**
     * A resolved provider profile.
     *
     * @param providerUserId the provider's stable subject id (Google {@code sub}, Facebook {@code
     *                       id}). This — not the email — is what identifies the external account:
     *                       emails get reassigned, subject ids do not.
     * @param emailVerified  whether the provider states it verified ownership of the address.
     *                       Carried explicitly rather than assumed, because auto-linking an
     *                       unverified assertion to an existing account hands that account to
     *                       whoever registered the address at a lax provider.
     */
    public record OAuthProfile(String provider,
                               String providerUserId,
                               String email,
                               String displayName,
                               boolean emailVerified,
                               String rawProfileJson) {
    }
}
