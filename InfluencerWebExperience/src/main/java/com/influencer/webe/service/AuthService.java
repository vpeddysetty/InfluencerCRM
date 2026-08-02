package com.influencer.webe.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.client.DaoUserClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private final DaoUserClient daoUserClient;
    private final OAuthProfileService oauthProfileService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(
            DaoUserClient daoUserClient,
            OAuthProfileService oauthProfileService,
            SessionService sessionService,
            ObjectMapper objectMapper) {
        this.daoUserClient = daoUserClient;
        this.oauthProfileService = oauthProfileService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    public AuthResponse signup(String email, String password, String brandName) {
        String normalizedEmail = normalizeEmail(email);
        if (daoUserClient.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        DaoUserClient.UserPayload payload = new DaoUserClient.UserPayload(
                null,
                normalizedEmail,
                passwordEncoder.encode(password),
                blankToNull(brandName),
                "{}",
                "owner",
                "free",
                null,
                null);

        DaoUserClient.UserRecord createdUser = daoUserClient.createUser(payload);
        SessionService.SessionInfo session = sessionService.createSession(createdUser.id(), createdUser.email(), "password");
        return AuthResponse.from(createdUser, session);
    }

    public AuthResponse login(String email, String password) {
        DaoUserClient.UserRecord user = daoUserClient.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        SessionService.SessionInfo session = sessionService.createSession(user.id(), user.email(), "password");
        return AuthResponse.from(user, session);
    }

    /**
     * Revokes the session's refresh token. The caller's access token remains valid until it expires
     * — the accepted trade-off of stateless verification, bounded by the short access-token TTL.
     */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken is required");
        }
        sessionService.invalidate(refreshToken);
    }

    /**
     * Exchanges a refresh token for a fresh access token, rotating the refresh token in the process.
     *
     * <p>The user is loaded <em>before</em> rotation so the new access token carries a correct email
     * claim, and so a deleted user cannot renew a session.
     */
    public AuthResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken is required");
        }

        UUID userId = sessionService.peekRefreshTokenUserId(refreshToken)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token"));
        DaoUserClient.UserRecord user = daoUserClient.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token"));

        SessionService.SessionInfo session = sessionService.refresh(refreshToken, user.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token"));
        return AuthResponse.from(user, session);
    }

    public AuthResponse signupWithGoogle(String accessToken, String fallbackEmail, String fallbackDisplayName, String brandName) {
        return signupWithSocial("google", accessToken, fallbackEmail, fallbackDisplayName, brandName);
    }

    public AuthResponse signupWithFacebook(String accessToken, String fallbackEmail, String fallbackDisplayName, String brandName) {
        return signupWithSocial("facebook", accessToken, fallbackEmail, fallbackDisplayName, brandName);
    }

    private AuthResponse signupWithSocial(String provider, String accessToken, String fallbackEmail, String fallbackDisplayName, String brandName) {
        OAuthProfileService.OAuthProfile profile = oauthProfileService.resolveProfile(provider, accessToken, fallbackEmail, fallbackDisplayName);
        DaoUserClient.UserRecord existing = daoUserClient.findByEmail(profile.email()).orElse(null);
        String customAttributes = mergeCustomAttributes(existing == null ? null : existing.customAttributes(), provider, profile);
        String resolvedBrandName = blankToNull(brandName);
        if (resolvedBrandName == null && existing != null) {
            resolvedBrandName = existing.brandName();
        }
        if (resolvedBrandName == null) {
            resolvedBrandName = profile.displayName();
        }

        DaoUserClient.UserPayload payload = new DaoUserClient.UserPayload(
                existing == null ? null : existing.id(),
                profile.email(),
                existing == null ? passwordEncoder.encode(UUID.randomUUID().toString()) : existing.passwordHash(),
                resolvedBrandName,
                customAttributes,
                "owner",
                existing == null ? "free" : existing.plan(),
                existing == null ? null : existing.createdAt(),
                Instant.now());

        DaoUserClient.UserRecord saved = existing == null ? daoUserClient.createUser(payload) : daoUserClient.updateUser(existing.id(), payload);
        SessionService.SessionInfo session = sessionService.createSession(saved.id(), saved.email(), provider);
        return AuthResponse.from(saved, session);
    }

    private String mergeCustomAttributes(String currentJson, String provider, OAuthProfileService.OAuthProfile profile) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (currentJson != null && !currentJson.isBlank()) {
            try {
                attributes.putAll(objectMapper.readValue(currentJson, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception ignored) {
                attributes.put("raw_custom_attributes", currentJson);
            }
        }

        Map<String, Object> oauth = new LinkedHashMap<>();
        oauth.put("provider", provider);
        oauth.put("providerUserId", profile.providerUserId());
        oauth.put("email", profile.email());
        oauth.put("displayName", profile.displayName());
        oauth.put("verifiedAt", Instant.now().toString());
        attributes.put("oauth", oauth);

        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize custom attributes", exception);
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        return email.trim().toLowerCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The session as the client sees it.
     *
     * <p>{@code brandId}, {@code brandName} and {@code role} describe the <em>active brand</em>, not
     * the user: an agency member can hold different roles on different brands, so these change when
     * the caller switches brand. {@code brandName} comes from the brands table now, no longer from
     * the superseded {@code users.brand_name} column.
     */
    public record AuthResponse(
            UUID userId,
            String email,
            UUID accountId,
            UUID brandId,
            String brandName,
            String role,
            String plan,
            String accessToken,
            String refreshToken,
            String tokenType,
            Instant issuedAt,
            Instant expiresAt) {

        public static AuthResponse from(DaoUserClient.UserRecord user, SessionService.SessionInfo session) {
            return new AuthResponse(
                    user.id(),
                    user.email(),
                    session.accountId(),
                    session.brandId(),
                    session.brandName() != null ? session.brandName() : user.brandName(),
                    session.role(),
                    user.plan(),
                    session.token(),
                    session.refreshToken(),
                    "Bearer",
                    session.issuedAt(),
                    session.expiresAt());
        }
    }
}
