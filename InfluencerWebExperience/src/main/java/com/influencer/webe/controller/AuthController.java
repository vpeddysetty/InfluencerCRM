package com.influencer.webe.controller;

import com.influencer.webe.service.AuthService;
import com.influencer.webe.service.OAuthFlowService;
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

    public AuthController(AuthService authService, OAuthFlowService oauthFlowService) {
        this.authService = authService;
        this.oauthFlowService = oauthFlowService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.AuthResponse signup(@Valid @RequestBody BrandSignupRequest request) {
        return authService.signup(request.email(), request.password(), request.brandName());
    }

    @PostMapping("/login")
    public AuthService.AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.accessToken());
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
    public AuthService.AuthResponse googleOAuthCallback(
            @RequestParam String code,
            @RequestParam String state) {
        return oauthFlowService.completeGoogle(code, state);
    }

    @GetMapping("/oauth/facebook/start")
    public ResponseEntity<Void> startFacebookOAuth(
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) String displayName) {
        return oauthFlowService.startFacebook(brandName, displayName);
    }

    @GetMapping("/oauth/facebook/callback")
    public AuthService.AuthResponse facebookOAuthCallback(
            @RequestParam String code,
            @RequestParam String state) {
        return oauthFlowService.completeFacebook(code, state);
    }

    public record BrandSignupRequest(
            @Email @NotBlank String email,
            @NotBlank String password,
            String brandName) {
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    public record LogoutRequest(@NotBlank String accessToken) {
    }

    public record SocialSignupRequest(
            String accessToken,
            @Email String fallbackEmail,
            String fallbackDisplayName,
            String brandName) {
    }
}
