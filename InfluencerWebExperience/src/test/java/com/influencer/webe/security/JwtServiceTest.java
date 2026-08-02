package com.influencer.webe.security;

import com.influencer.webe.config.WebExperienceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the token layer that replaced the in-memory session map.
 *
 * <p>These assertions exist because the previous implementation could not make any of them: opaque
 * random UUIDs carried no claims and no signature, so a token could be neither validated
 * independently nor trusted to state who its bearer was.
 */
class JwtServiceTest {

    private JwtService jwtService;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(properties(30));
    }

    private WebExperienceProperties properties(long accessTokenTtlMinutes) {
        WebExperienceProperties properties = new WebExperienceProperties();
        properties.setAccessTokenTtlMinutes(accessTokenTtlMinutes);
        // These tests deliberately want a fresh, independent key per instance — that is how
        // "a token signed by another instance is rejected" is expressed. Production refuses to
        // start without a configured key, so the escape hatch is opted into explicitly here.
        properties.setAllowEphemeralJwtKey(true);
        return properties;
    }

    private TenantContext contextFor(UUID userId) {
        return new TenantContext(
                userId, userId, userId, "user@example.com", AccountRole.OWNER,
                Set.of(Permission.CREATOR_READ), Set.of(userId));
    }

    @Test
    @DisplayName("a freshly issued token round-trips with all tenancy claims intact")
    void issuesAndVerifiesToken() {
        String token = jwtService.issueAccessToken(contextFor(USER_ID), "password");

        Optional<TenantContext> verified = jwtService.verify(token);

        assertThat(verified).isPresent();
        assertThat(verified.get().userId()).isEqualTo(USER_ID);
        assertThat(verified.get().accountId()).isEqualTo(USER_ID);
        assertThat(verified.get().brandId()).isEqualTo(USER_ID);
        assertThat(verified.get().email()).isEqualTo("user@example.com");
        assertThat(verified.get().role()).isEqualTo(AccountRole.OWNER);
        assertThat(verified.get().permissions()).containsExactly(Permission.CREATOR_READ);
        assertThat(verified.get().accessibleBrandIds()).containsExactly(USER_ID);
    }

    @Test
    @DisplayName("a token whose payload has been tampered with is rejected")
    void rejectsTamperedToken() {
        String token = jwtService.issueAccessToken(contextFor(USER_ID), "password");

        // Swap the payload segment for one naming a different user, keeping the original signature.
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + OTHER_USER_ID + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(jwtService.verify(forged)).isEmpty();
    }

    @Test
    @DisplayName("a token signed by a different instance's key is rejected")
    void rejectsTokenSignedByAnotherKey() {
        // A separate service generates its own ephemeral key, standing in for an attacker
        // presenting a well-formed token they signed themselves.
        JwtService foreignService = new JwtService(properties(30));
        String foreignToken = foreignService.issueAccessToken(contextFor(USER_ID), "password");

        assertThat(jwtService.verify(foreignToken)).isEmpty();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpiredToken() {
        JwtService shortLived = new JwtService(properties(-1));
        String alreadyExpired = shortLived.issueAccessToken(contextFor(USER_ID), "password");

        assertThat(shortLived.verify(alreadyExpired)).isEmpty();
    }

    @Test
    @DisplayName("malformed, empty and null tokens are rejected rather than throwing")
    void rejectsMalformedTokens() {
        assertThat(jwtService.verify(null)).isEmpty();
        assertThat(jwtService.verify("")).isEmpty();
        assertThat(jwtService.verify("   ")).isEmpty();
        assertThat(jwtService.verify("not-a-jwt")).isEmpty();
        assertThat(jwtService.verify("a.b.c")).isEmpty();
    }

    @Test
    @DisplayName("one user's token never resolves to another user's identity")
    void tokensAreBoundToTheirSubject() {
        String tokenA = jwtService.issueAccessToken(contextFor(USER_ID), "password");
        String tokenB = jwtService.issueAccessToken(contextFor(OTHER_USER_ID), "password");

        assertThat(jwtService.verify(tokenA).orElseThrow().userId()).isEqualTo(USER_ID);
        assertThat(jwtService.verify(tokenB).orElseThrow().userId()).isEqualTo(OTHER_USER_ID);
        assertThat(tokenA).isNotEqualTo(tokenB);
    }

    @Test
    @DisplayName("startup fails loudly when no signing key is configured")
    void refusesToStartWithoutAConfiguredKey() {
        WebExperienceProperties properties = new WebExperienceProperties();
        properties.setAccessTokenTtlMinutes(30);
        // Deliberately does NOT opt into an ephemeral key.

        // An ephemeral key cannot be verified by another instance or by an extracted service, so
        // the resulting 401s look like a session bug. Failing at boot is far cheaper to diagnose
        // than intermittent logouts in production.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-signing-key is not configured");
    }

    @Test
    @DisplayName("a configured key is reused, so tokens survive a restart")
    void configuredKeySurvivesRestart() {
        // Stand in for "the same key is configured on two instances / across a restart".
        WebExperienceProperties first = new WebExperienceProperties();
        first.setAccessTokenTtlMinutes(30);
        first.setAllowEphemeralJwtKey(true);
        JwtService generator = new JwtService(first);

        String jwk = generator.exportSigningKeyForTesting();

        WebExperienceProperties configured = new WebExperienceProperties();
        configured.setAccessTokenTtlMinutes(30);
        configured.setJwtSigningKey(jwk);

        JwtService instanceA = new JwtService(configured);
        JwtService instanceB = new JwtService(configured);

        String token = instanceA.issueAccessToken(contextFor(USER_ID), "password");

        // The whole point: instance B verifies a token instance A minted.
        assertThat(instanceB.verify(token)).isPresent();
        assertThat(instanceB.verify(token).orElseThrow().userId()).isEqualTo(USER_ID);
    }
}
