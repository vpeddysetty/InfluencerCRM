package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handoff code is what replaces putting tokens in a URL, so its two guarantees — single use and
 * unguessability — are the whole reason the redirect is safe.
 */
class OAuthHandoffServiceTest {

    private final OAuthHandoffService service = new OAuthHandoffService();

    @Test
    @DisplayName("a code returns the sign-in exactly once")
    void codeIsSingleUse() {
        String code = service.store(sampleAuth());

        assertThat(service.consume(code)).isPresent();

        // The replay. An attacker who reads the code from a log, browser history or the redirect
        // itself must find nothing — this is what makes a code in a URL acceptable when a token
        // would not be.
        assertThat(service.consume(code))
                .as("a replayed handoff code must not yield a second session")
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown code yields nothing")
    void unknownCodeIsRejected() {
        service.store(sampleAuth());

        assertThat(service.consume("not-a-real-code")).isEmpty();
        assertThat(service.consume(null)).isEmpty();
        assertThat(service.consume("")).isEmpty();
    }

    @Test
    @DisplayName("codes are unique and long enough not to be guessed")
    void codesAreUnguessable() {
        String first = service.store(sampleAuth());
        String second = service.store(sampleAuth());

        assertThat(first).isNotEqualTo(second);
        // 32 random bytes, base64url without padding.
        assertThat(first.length()).isGreaterThanOrEqualTo(43);
    }

    @Test
    @DisplayName("each stored sign-in comes back to its own code")
    void codesDoNotCrossOver() {
        AuthService.AuthResponse alice = sampleAuth();
        AuthService.AuthResponse bob = sampleAuth();

        String aliceCode = service.store(alice);
        String bobCode = service.store(bob);

        assertThat(service.consume(aliceCode)).containsSame(alice);
        assertThat(service.consume(bobCode)).containsSame(bob);
    }

    /**
     * A distinct instance per call; identity is what the cross-over test asserts on, so these must
     * not be shared or equal.
     */
    private AuthService.AuthResponse sampleAuth() {
        java.time.Instant now = java.time.Instant.now();
        return new AuthService.AuthResponse(
                java.util.UUID.randomUUID(),
                "user@example.com",
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                "Example Brand",
                "owner",
                "free",
                "access-token",
                "refresh-token",
                "Bearer",
                now,
                now.plusSeconds(1800));
    }
}
