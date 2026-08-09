package com.influencer.webe.identity.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.influencer.webe.identity.application.AuthService;
import com.influencer.webe.identity.application.OAuthFlowService;
import com.influencer.webe.identity.application.OAuthHandoffService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final OAuthFlowService oauthFlowService;
    private final OAuthHandoffService oauthHandoffService;

    public AuthController(AuthService authService,
                          OAuthFlowService oauthFlowService,
                          OAuthHandoffService oauthHandoffService) {
        this.authService = authService;
        this.oauthFlowService = oauthFlowService;
        this.oauthHandoffService = oauthHandoffService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.AuthResponse signup(@Valid @RequestBody BrandSignupRequest request) {
        return authService.signup(request.email(), request.password(), request.brandName(), request.accountType());
    }

    @PostMapping("/login")
    public AuthService.AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }

    @PostMapping("/refresh")
    public AuthService.AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/google/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.AuthResponse googleSignup(@Valid @RequestBody SocialSignupRequest request) {
        return authService.signupWithGoogle(request.accessToken(), request.fallbackEmail(), request.fallbackDisplayName(), request.brandName());
    }

    @PostMapping("/facebook/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.AuthResponse facebookSignup(@Valid @RequestBody SocialSignupRequest request) {
        return authService.signupWithFacebook(request.accessToken(), request.fallbackEmail(), request.fallbackDisplayName(), request.brandName());
    }

    @GetMapping("/oauth/google/start")
    public ResponseEntity<Void> startGoogleOAuth(
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) String displayName) {
        return oauthFlowService.startGoogle(brandName, displayName);
    }

    @GetMapping("/oauth/google/callback")
    public ResponseEntity<Void> googleOAuthCallback(
            @RequestParam String code,
            @RequestParam String state) {
        return oauthFlowService.completeGoogle(code, state);
    }

    /**
     * Redeems a single-use OAuth handoff code for the completed sign-in.
     *
     * <p>Called server-to-server by the DPS, never by a browser: the tokens in this response are
     * exactly what must not travel through a URL or reach JavaScript. The code is consumed on read,
     * so a replay finds nothing.
     */
    @PostMapping("/oauth/handoff")
    public AuthService.AuthResponse redeemOAuthHandoff(@Valid @RequestBody OAuthHandoffRequest request) {
        return oauthHandoffService.consume(request.handoff())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid or expired OAuth handoff code"));
    }

    @GetMapping("/oauth/facebook/start")
    public ResponseEntity<Void> startFacebookOAuth(
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) String displayName) {
        return oauthFlowService.startFacebook(brandName, displayName);
    }

    @GetMapping("/oauth/facebook/callback")
    public ResponseEntity<Void> facebookOAuthCallback(
            @RequestParam String code,
            @RequestParam String state) {
        return oauthFlowService.completeFacebook(code, state);
    }

    /**
     * A signup request.
     *
     * <p>{@code accountType} is optional and defaults to {@code brand}, so existing clients are
     * unaffected. Unknown properties are rejected: this payload previously accepted — and silently
     * ignored — fields like {@code role}, which meant a caller asking for something the endpoint
     * does not support received a 200 and a different account than they asked for.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} would not achieve this: Spring Boot
     * disables {@code FAIL_ON_UNKNOWN_PROPERTIES} globally, and that annotation only declines to
     * re-enable it. {@code @JsonAnySetter} is what actually sees the leftover field, so the
     * rejection is explicit rather than a mapper setting a future config change could silently
     * undo.
     */
    public record BrandSignupRequest(
            @Email @NotBlank String email,
            @NotBlank String password,
            String brandName,
            String accountType) {

        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unrecognised signup field: " + name);
        }
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    public record LogoutRequest(@NotBlank String refreshToken) {
    }

    public record OAuthHandoffRequest(@NotBlank String handoff) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record SocialSignupRequest(
            String accessToken,
            @Email String fallbackEmail,
            String fallbackDisplayName,
            String brandName) {
    }
}
