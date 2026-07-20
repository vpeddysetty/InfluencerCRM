package com.influencer.webe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class OAuthProfileService {
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
                if (fallbackEmail == null || fallbackEmail.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to resolve social profile and no fallback email was provided", exception);
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

    @FunctionalInterface
    private interface ProfileMapper {
        OAuthProfile map(JsonNode node);
    }

    public record OAuthProfile(String provider, String providerUserId, String email, String displayName, String rawProfileJson) {
    }
}
