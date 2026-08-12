package com.influencer.webe.identity.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.influencer.webe.identity.application.AuthService;
import com.influencer.webe.identity.application.ConsentService;
import com.influencer.webe.identity.application.OAuthFlowService;
import com.influencer.webe.identity.application.OAuthHandoffService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ConsentService consentService;

    public AuthController(AuthService authService,
                          OAuthFlowService oauthFlowService,
                          OAuthHandoffService oauthHandoffService,
                          ConsentService consentService) {
        this.authService = authService;
        this.oauthFlowService = oauthFlowService;
        this.oauthHandoffService = oauthHandoffService;
        this.consentService = consentService;
    }

    /**
     * Brand and agency signup — the two share this endpoint, distinguished by {@code accountType}.
     *
     * <p>Consent is checked BEFORE the account is created so a refusal creates nothing, and recorded
     * AFTER, because the record needs the user id that only exists once the account does.
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.AuthResponse signup(@Valid @RequestBody BrandSignupRequest request,
                                           HttpServletRequest httpRequest) {
        consentService.requireAccepted(request.acceptedTerms());

        AuthService.AuthResponse response =
                authService.signup(request.email(), request.password(), request.brandName(), request.accountType());

        consentService.recordSignupConsent(
                ConsentService.SUBJECT_USER,
                response.userId(),
                response.email(),
                // The account type is the only thing distinguishing a brand signup from an agency
                // one, and it is worth being able to answer "who consented, as what" without a join.
                AuthService.ACCOUNT_TYPE_AGENCY.equals(normalizedType(request.accountType()))
                        ? "agency_signup"
                        : "brand_signup",
                httpRequest,
                null);

        return response;
    }

    /** Mirrors AuthService's normalisation for the source label only; that class still validates. */
    private String normalizedType(String accountType) {
        return accountType == null ? "" : accountType.trim().toLowerCase();
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

    /**
     * @param acceptedTerms carried as a query parameter because this leg is a browser NAVIGATION,
     *     not a fetch — there is no request body to put it in. The DPS forwards it from the landing
     *     page, where the checkbox lives; OAuthFlowService rejects the request if it is not true.
     */
    @GetMapping("/oauth/google/start")
    public ResponseEntity<Void> startGoogleOAuth(
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false, defaultValue = "false") boolean acceptedTerms,
            HttpServletRequest httpRequest) {
        return oauthFlowService.startGoogle(brandName, displayName, acceptedTerms,
                consentService.clientIp(httpRequest), consentService.userAgent(httpRequest));
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

    /** As the Google leg; see {@link #startGoogleOAuth} for why consent is a query parameter here. */
    @GetMapping("/oauth/facebook/start")
    public ResponseEntity<Void> startFacebookOAuth(
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false, defaultValue = "false") boolean acceptedTerms,
            HttpServletRequest httpRequest) {
        return oauthFlowService.startFacebook(brandName, displayName, acceptedTerms,
                consentService.clientIp(httpRequest), consentService.userAgent(httpRequest));
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
    /**
     * @param acceptedTerms whether the signup form's consent checkbox was ticked. Boxed, not
     *     primitive, so that "absent" and "false" stay distinguishable — a primitive would default a
     *     missing field to {@code false} and report it as a refusal, which is the right outcome by
     *     luck rather than by design. {@code ConsentService.requireAccepted} rejects both.
     */
    public record BrandSignupRequest(
            @Email @NotBlank String email,
            @NotBlank String password,
            String brandName,
            String accountType,
            Boolean acceptedTerms) {

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
