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
    private final ConsentService consentService;

    public OAuthFlowService(
            WebExperienceProperties properties,
            OAuthStateService oauthStateService,
            OAuthProfileService oauthProfileService,
            AuthService authService,
            OAuthHandoffService handoffService,
            ConsentService consentService) {
        this.properties = properties;
        this.oauthStateService = oauthStateService;
        this.oauthProfileService = oauthProfileService;
        this.authService = authService;
        this.handoffService = handoffService;
        this.consentService = consentService;
    }

    public ResponseEntity<Void> startGoogle(String brandName, String displayName) {
        return startGoogle(brandName, displayName, false, null, null);
    }

    /**
     * Begins a Google sign-in, having first checked that the person consented.
     *
     * <p>The check is here rather than in the callback because this is the last point at which a
     * refusal costs nothing: after the redirect the browser is at Google, and rejecting on the way
     * back would mean explaining a failure to someone who has already authenticated.
     */
    public ResponseEntity<Void> startGoogle(String brandName,
                                            String displayName,
                                            boolean acceptedTerms,
                                            String ipAddress,
                                            String userAgent) {
        consentService.requireAccepted(acceptedTerms);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(buildAuthorizationUrl("google", brandName, displayName, acceptedTerms, ipAddress, userAgent)))
                .build();
    }

    public ResponseEntity<Void> startFacebook(String brandName, String displayName) {
        return startFacebook(brandName, displayName, false, null, null);
    }

    /** As {@link #startGoogle(String, String, boolean, String, String)}, for Facebook. */
    public ResponseEntity<Void> startFacebook(String brandName,
                                              String displayName,
                                              boolean acceptedTerms,
                                              String ipAddress,
                                              String userAgent) {
        consentService.requireAccepted(acceptedTerms);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(buildAuthorizationUrl("facebook", brandName, displayName, acceptedTerms, ipAddress, userAgent)))
                .build();
    }

    /**
     * Begins a provider link for a caller who is already signed in.
     *
     * <p>No consent check, unlike the signup paths: the terms were accepted when the account was
     * created, and adding a second way to sign in to it is not a new agreement.
     */
    public String authorizationUrlForLink(String provider, java.util.UUID userId) {
        OAuthStateService.PendingOAuthRequest request = oauthStateService.createForLink(provider, userId);
        return authorizationUrlFor(provider, request);
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

            // A LINK, not a sign-in: the account already exists and the caller was signed in to it
            // when the flow started. Attach the identity and send them back to settings; minting a
            // fresh session here would be pointless, since they already hold one.
            if (request.linkUserId() != null) {
                authService.linkProvider(provider, request.linkUserId(), accessToken);
                return redirectToDps("/dps/auth/oauth/complete?linked=" + encode(provider));
            }

            AuthService.AuthResponse auth = "google".equals(provider)
                    ? authService.signupWithGoogle(accessToken, null, request.displayName(), request.brandName())
                    : authService.signupWithFacebook(accessToken, null, request.displayName(), request.brandName());

            // Now that the account exists there is a user id to attach the consent to. The consent
            // itself was given before the redirect and has been carried in the pending request since
            // — see OAuthStateService.create for why it travels server-side rather than in `state`.
            //
            // This also fires for a RETURNING user, because signupWithSocial doubles as sign-in and
            // does not distinguish the two. That is the safe direction: the unique index collapses a
            // repeat acceptance of the same document version to the existing row, so a returning
            // user re-consenting is a no-op, while missing a first-time signup would lose the record.
            if (request.acceptedTerms()) {
                consentService.recordSignupConsent(
                        ConsentService.SUBJECT_USER,
                        auth.userId(),
                        auth.email(),
                        provider + "_oauth_signup",
                        // No metadata beyond the source.
                        null,
                        // The IP and user agent were captured at /start; the current request is the
                        // provider's redirect, so reading them from it now would record the wrong
                        // client. These stored values are the authoritative ones.
                        request.ipAddress(),
                        request.userAgent());
            }

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

    private String buildAuthorizationUrl(String provider,
                                         String brandName,
                                         String displayName,
                                         boolean acceptedTerms,
                                         String ipAddress,
                                         String userAgent) {
        OAuthStateService.PendingOAuthRequest request =
                oauthStateService.create(provider, brandName, displayName, acceptedTerms, ipAddress, userAgent);
        return authorizationUrlFor(provider, request);
    }

    /**
     * Builds the provider's authorization URL for an already-opened pending request.
     *
     * <p>Split out so the signup and link paths cannot drift: both must send the same client id,
     * redirect URI and scopes, and the only difference between them belongs in the pending request
     * rather than in the URL.
     */
    private String authorizationUrlFor(String provider, OAuthStateService.PendingOAuthRequest request) {
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
            // Configured rather than literal: which scopes are valid depends on the app's type in
            // the Meta dashboard, not on this code. See WebExperienceProperties.Facebook#scope —
            // a Business-type app refuses "email public_profile" on its own.
            //
            // Comma-separated on the wire. Meta accepts either separator, but its own documentation
            // and error messages use commas, and a space-separated list is what the previous literal
            // sent — so commas keep what we send and what Meta reports back directly comparable.
            scope = normalizeScopes(facebook.getScope());
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

    /**
     * Accepts a scope list written with commas, spaces, or both, and emits a comma-separated one.
     *
     * <p>The property is edited by hand in a compose file and in the Meta dashboard's own notation,
     * so tolerating whichever separator someone reaches for is worth more than insisting on one.
     * Blank entries are dropped: a trailing comma is a typo, not a request for an empty permission,
     * and Meta rejects the whole authorization with {@code Invalid Scopes} if one arrives.
     */
    private String normalizeScopes(String configured) {
        if (configured == null || configured.isBlank()) {
            // Deliberately not silently substituting a default. An empty scope is a configuration
            // mistake, and Meta's error for it names the app rather than the setting — so failing
            // here, with the property name, is the faster diagnosis.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "facebook.scope is not configured");
        }
        String[] parts = configured.trim().split("[,\\s]+");
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(part);
        }
        if (joined.length() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "facebook.scope is not configured");
        }
        return joined.toString();
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