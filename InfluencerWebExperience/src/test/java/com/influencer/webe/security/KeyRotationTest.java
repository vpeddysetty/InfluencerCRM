package com.influencer.webe.security;

import com.influencer.webe.config.WebExperienceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves a signing key can be rotated without logging anyone out.
 *
 * <p>That property is the entire point. With a single key, replacing it invalidates every token
 * already issued — so rotation becomes an outage, operators avoid it, and a credential that should
 * change regularly never does. These tests fail if that regression ever returns.
 */
class KeyRotationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private WebExperienceProperties properties(String activeKey, String previousKeys) {
        WebExperienceProperties properties = new WebExperienceProperties();
        properties.setAccessTokenTtlMinutes(30);
        properties.setJwtSigningKey(activeKey);
        properties.setJwtPreviousKeys(previousKeys);
        // Only used where a key is being generated to stand in for "some other deployment's key".
        properties.setAllowEphemeralJwtKey(activeKey == null);
        return properties;
    }

    private TenantContext context() {
        return new TenantContext(USER_ID, USER_ID, USER_ID, "user@example.com",
                AccountRole.OWNER, RolePermissions.forRole(AccountRole.OWNER), Set.of(USER_ID));
    }

    /** A throwaway service purely to mint a key we can then configure elsewhere. */
    private String generateKey() {
        return new JwtService(properties(null, null)).exportSigningKeyForTesting();
    }

    @Test
    @DisplayName("a token signed by the old key still verifies after rotation")
    void rotationDoesNotLogAnyoneOut() {
        String oldKey = generateKey();
        String newKey = generateKey();

        // Before: a user signs in and receives a token from the old key.
        JwtService before = new JwtService(properties(oldKey, null));
        String tokenIssuedBeforeRotation = before.issueAccessToken(context(), "password");
        assertThat(before.verify(tokenIssuedBeforeRotation)).isPresent();

        // Rotate: the new key signs, the old one is retained for verification only.
        JwtService after = new JwtService(properties(newKey, publicHalfOf(oldKey)));

        // The user is still logged in. Without the keyset this assertion fails, and every user is
        // signed out the moment a rotation happens.
        assertThat(after.verify(tokenIssuedBeforeRotation))
                .as("a token issued before rotation must survive it")
                .isPresent();
        assertThat(after.verify(tokenIssuedBeforeRotation).orElseThrow().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("new tokens are signed by the new key")
    void newTokensUseTheNewKey() {
        String oldKey = generateKey();
        String newKey = generateKey();

        JwtService after = new JwtService(properties(newKey, publicHalfOf(oldKey)));
        String freshToken = after.issueAccessToken(context(), "password");

        // A service that only knows the new key must accept it — otherwise the rotation has not
        // actually taken effect and the old key is still doing the signing.
        JwtService newKeyOnly = new JwtService(properties(newKey, null));
        assertThat(newKeyOnly.verify(freshToken)).isPresent();
    }

    @Test
    @DisplayName("once the old key is dropped, its tokens stop verifying")
    void retiringTheOldKeyCompletesTheRotation() {
        String oldKey = generateKey();
        String newKey = generateKey();

        String tokenFromOldKey = new JwtService(properties(oldKey, null))
                .issueAccessToken(context(), "password");

        // Rotation complete: the predecessor has been removed from configuration. This is what
        // makes rotation meaningful — the old key can no longer be used to mint or present tokens.
        JwtService afterRetirement = new JwtService(properties(newKey, null));
        assertThat(afterRetirement.verify(tokenFromOldKey))
                .as("after the old key is retired its tokens must be rejected")
                .isEmpty();
    }

    @Test
    @DisplayName("a key that was never configured is rejected, rotation or not")
    void unknownKeysAreStillRejected() {
        String ourKey = generateKey();
        String strangerKey = generateKey();

        String forged = new JwtService(properties(strangerKey, null))
                .issueAccessToken(context(), "password");

        // Multi-key verification must widen trust to *configured* predecessors only. A token from
        // an unrelated key is exactly what an attacker would present.
        JwtService ours = new JwtService(properties(ourKey, publicHalfOf(generateKey())));
        assertThat(ours.verify(forged)).isEmpty();
    }

    @Test
    @DisplayName("several predecessors can be trusted at once")
    void supportsMultiplePredecessors() {
        String oldest = generateKey();
        String middle = generateKey();
        String active = generateKey();

        String fromOldest = new JwtService(properties(oldest, null)).issueAccessToken(context(), "password");
        String fromMiddle = new JwtService(properties(middle, null)).issueAccessToken(context(), "password");

        // Two rotations in quick succession — an incident response, say — must not log anyone out
        // either.
        JwtService current = new JwtService(
                properties(active, publicHalfOf(oldest) + "," + publicHalfOf(middle)));

        assertThat(current.verify(fromOldest)).isPresent();
        assertThat(current.verify(fromMiddle)).isPresent();
        assertThat(current.verify(current.issueAccessToken(context(), "password"))).isPresent();
    }

    @Test
    @DisplayName("the JWKS endpoint publishes public halves only, never a private key")
    void jwksExposesNoPrivateMaterial() {
        String active = generateKey();
        String previous = generateKey();

        JwtService service = new JwtService(properties(active, publicHalfOf(previous)));
        String published = service.publicJwkSet().toJSONObject(true).toString();

        // 'd' is the RSA private exponent; 'p' and 'q' are the primes. Any of them appearing here
        // would mean the endpoint is handing out the signing key itself.
        assertThat(published).doesNotContain("\"d\"");
        assertThat(published).doesNotContain("\"p\"");
        assertThat(published).doesNotContain("\"q\"");
        // Both keys must be advertised, so a verifier can validate tokens from either during the
        // rotation window.
        assertThat(service.publicJwkSet().getKeys()).hasSize(2);
    }

    @Test
    @DisplayName("a malformed predecessor is skipped rather than preventing startup")
    void malformedPredecessorDoesNotBreakStartup() {
        String active = generateKey();

        // A stale or hand-edited config entry must not turn into an outage: the active key still
        // works, so the service starts and only that predecessor's tokens are rejected.
        JwtService service = new JwtService(properties(active, "{not-valid-json}"));
        assertThat(service.verify(service.issueAccessToken(context(), "password"))).isPresent();
    }

    @Test
    @DisplayName("a signing key without its private half is rejected at startup")
    void publicOnlyActiveKeyIsRejected() {
        String publicOnly = publicHalfOf(generateKey());

        // Configuring the public half as the active key would leave the service unable to sign.
        // Failing at boot is far cheaper to diagnose than every login failing later.
        assertThatThrownBy(() -> new JwtService(properties(publicOnly, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private key");
    }

    /** Strips the private half, mirroring what an operator puts in {@code jwt-previous-keys}. */
    private String publicHalfOf(String jwk) {
        try {
            return com.nimbusds.jose.jwk.RSAKey.parse(jwk).toPublicJWK().toJSONString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to derive the public half", exception);
        }
    }
}
