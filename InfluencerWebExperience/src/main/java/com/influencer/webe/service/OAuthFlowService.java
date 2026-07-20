package com.influencer.webe.service;

import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class OAuthFlowService {
    private final WebExperienceProperties properties;
    private final OAuthStateService oauthStateService;
    private final OAuthProfileService oauthProfileService;
    private final AuthService authService;

    public OAuthFlowService(
            WebExperienceProperties properties,
            OAuthStateService oauthStateService,
            OAuthProfileService oauthProfileService,
            AuthService authService) {
        this.properties = properties;
        this.oauthStateService = oauthStateService;
        this.oauthProfileService = oauthProfileService;
        this.authService = authService;
    }

    public ResponseEntity<Void> startGoogle(String brandName, String displayName) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(buildAuthorizationUrl("google", brandName, displayName))).build();
    }

    public ResponseEntity<Void> startFacebook(String brandName, String displayName) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(buildAuthorizationUrl("facebook", brandName, displayName))).build();
    }

    public AuthService.AuthResponse completeGoogle(String code, String state) {
        OAuthStateService.PendingOAuthRequest request = oauthStateService.consume(state);
        if (!"google".equals(request.provider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state provider mismatch");
        }
        String accessToken = oauthProfileService.exchangeAuthorizationCode("google", code, properties.getOauth().getGoogle().getRedirectUri());
        return authService.signupWithGoogle(accessToken, null, request.displayName(), request.brandName());
    }

    public AuthService.AuthResponse completeFacebook(String code, String state) {
        OAuthStateService.PendingOAuthRequest request = oauthStateService.consume(state);
        if (!"facebook".equals(request.provider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state provider mismatch");
        }
        String accessToken = oauthProfileService.exchangeAuthorizationCode("facebook", code, properties.getOauth().getFacebook().getRedirectUri());
        return authService.signupWithFacebook(accessToken, null, request.displayName(), request.brandName());
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