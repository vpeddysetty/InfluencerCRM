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
    private final OAuthHandoffService handoffService;

    public OAuthFlowService(
            WebExperienceProperties properties,
            OAuthStateService oauthStateService,
            OAuthProfileService oauthProfileService,
            AuthService authService,
            OAuthHandoffService handoffService) {
        this.properties = properties;
        this.oauthStateService = oauthStateService;
        this.oauthProfileService = oauthProfileService;
        this.authService = authService;
        this.handoffService = handoffService;
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
     * Completes the authorization-code exchange and hands the result to the DPS.
     *
     * <p>The browser is redirected to the DPS carrying a single-use handoff code — never the tokens
     * themselves. The DPS redeems that code server-to-server and converts the sign-in into an
     * httpOnly cookie session.
     *
     * <p>This previously redirected to a UI page with the tokens base64'd into the URL fragment, for
     * a popup to {@code postMessage} to its opener. That put an access and refresh token into a URL:
     * readable by any script on the landing page, and retained in browser history. Handing them to
     * the DPS instead is the whole reason that service exists.
     *
     * <p>Errors still redirect rather than returning a raw 4xx, so the popup always lands somewhere
     * that can render a message instead of showing the browser's error page.
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
            return redirectToDps("/dps/auth/oauth/complete?handoff=" + encode(handoffService.store(auth)));
        } catch (ResponseStatusException exception) {
            String reason = exception.getReason() == null ? "OAuth sign-in failed" : exception.getReason();
            return redirectToDps("/dps/auth/oauth/complete?error=" + encode(reason));
        } catch (IllegalArgumentException exception) {
            // Thrown when an unverified provider email collides with an existing account. The
            // message explains how to link deliberately, so it is worth surfacing rather than
            // flattening into a generic failure.
            String reason = exception.getMessage() == null ? "OAuth sign-in failed" : exception.getMessage();
            return redirectToDps("/dps/auth/oauth/complete?error=" + encode(reason));
        } catch (Exception exception) {
            return redirectToDps("/dps/auth/oauth/complete?error=" + encode("OAuth sign-in failed"));
        }
    }

    private ResponseEntity<Void> redirectToDps(String pathAndQuery) {
        String base = properties.getDpsBaseUrl();
        if (base == null || base.isBlank()) {
            base = "http://localhost:8090";
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(base.replaceAll("/+$", "") + pathAndQuery))
                .build();
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