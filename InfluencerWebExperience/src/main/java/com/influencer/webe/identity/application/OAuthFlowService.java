package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class OAuthFlowService {
    private final WebExperienceProperties properties;
    private final OAuthStateService oauthStateService;
    private final OAuthProfileService oauthProfileService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public OAuthFlowService(
            WebExperienceProperties properties,
            OAuthStateService oauthStateService,
            OAuthProfileService oauthProfileService,
            AuthService authService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.oauthStateService = oauthStateService;
        this.oauthProfileService = oauthProfileService;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<Void> startGoogle(String brandName, String displayName) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(buildAuthorizationUrl("google", brandName, displayName))).build();
    }

    public ResponseEntity<Void> startFacebook(String brandName, String displayName) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(buildAuthorizationUrl("facebook", brandName, displayName))).build();
    }

    public ResponseEntity<Void> completeGoogle(String code, String state) {
        return completeSocial("google", code, state);
    }

    public ResponseEntity<Void> completeFacebook(String code, String state) {
        return completeSocial("facebook", code, state);
    }

    /**
     * Completes the authorization-code exchange and redirects the browser (the OAuth popup)
     * back to a same-origin UI page, carrying the result in the URL fragment so the popup can
     * postMessage it to the opener. Errors redirect to the same page with an {@code error}
     * fragment instead of surfacing a raw 4xx/5xx, so the popup always lands somewhere readable.
     */
    private ResponseEntity<Void> completeSocial(String provider, String code, String state) {
        try {
            OAuthStateService.PendingOAuthRequest request = oauthStateService.consume(state);
            if (!provider.equals(request.provider())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state provider mismatch");
            }
            String redirectUri = "google".equals(provider)
                    ? properties.getOauth().getGoogle().getRedirectUri()
                    : properties.getOauth().getFacebook().getRedirectUri();
            String accessToken = oauthProfileService.exchangeAuthorizationCode(provider, code, redirectUri);
            AuthService.AuthResponse auth = "google".equals(provider)
                    ? authService.signupWithGoogle(accessToken, null, request.displayName(), request.brandName())
                    : authService.signupWithFacebook(accessToken, null, request.displayName(), request.brandName());
            return redirectToUi(uiCallbackUrl() + "#result=" + encodeResult(auth));
        } catch (ResponseStatusException exception) {
            String reason = exception.getReason() == null ? "OAuth sign-in failed" : exception.getReason();
            return redirectToUi(uiCallbackUrl() + "#error=" + encode(reason));
        } catch (Exception exception) {
            return redirectToUi(uiCallbackUrl() + "#error=" + encode("OAuth sign-in failed"));
        }
    }

    private ResponseEntity<Void> redirectToUi(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private String uiCallbackUrl() {
        String base = properties.getUiBaseUrl();
        if (base == null || base.isBlank()) {
            base = "http://localhost:5173";
        }
        return base.replaceAll("/+$", "") + "/oauth-callback.html";
    }

    private String encodeResult(AuthService.AuthResponse auth) {
        try {
            String json = objectMapper.writeValueAsString(auth);
            // base64url so tokens/JSON survive the URL fragment without escaping surprises.
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize auth response", exception);
        }
    }

    private String buildAuthorizationUrl(String provider, String brandName, String displayName) {
        OAuthStateService.PendingOAuthRequest request = oauthStateService.create(provider, brandName, displayName);
        WebExperienceProperties.Google google = properties.getOauth().getGoogle();
        WebExperienceProperties.Facebook facebook = properties.getOauth().getFacebook();

        String authorizationUri;
        String clientId;
        String redirectUri;
        String scope;

        if ("google".equals(provider)) {
            authorizationUri = requireConfigured(google.getAuthorizationUri(), "google.authorization-uri");
            clientId = requireConfigured(google.getClientId(), "google.client-id");
            redirectUri = requireConfigured(google.getRedirectUri(), "google.redirect-uri");
            scope = "openid email profile";
        } else if ("facebook".equals(provider)) {
            authorizationUri = requireConfigured(facebook.getAuthorizationUri(), "facebook.authorization-uri");
            clientId = requireConfigured(facebook.getClientId(), "facebook.client-id");
            redirectUri = requireConfigured(facebook.getRedirectUri(), "facebook.redirect-uri");
            scope = "email public_profile";
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
        }

        return authorizationUri
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(scope)
                + "&state=" + encode(request.state());
    }

    private String requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank() || "replace-me".equalsIgnoreCase(value.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, propertyName + " is not configured");
        }
        return value.trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}