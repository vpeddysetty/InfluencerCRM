package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guessing a creator's password gets slow fast (roadmap PR-43).
 *
 * <p>The property that matters most is not tested here but at the call site: the limiter is
 * consulted BEFORE BCrypt. That ordering is what stops the endpoint being a denial-of-service
 * amplifier, since BCrypt costs ~100ms by design and a refused attempt must not.
 */
class LoginAttemptLimiterTest {

    @Test
    @DisplayName("a few failures are fine; five in a row are not")
    void locksAfterRepeatedFailures() {
        // Five is chosen so somebody mistyping twice and then getting it right is never stopped.
        LoginAttemptLimiter limiter = new LoginAttemptLimiter();

        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("creator@example.com");
            assertThat(limiter.allow("creator@example.com"))
                    .as("a person mistyping must not be locked out after %d attempts", i + 1)
                    .isTrue();
        }

        limiter.recordFailure("creator@example.com");
        assertThat(limiter.allow("creator@example.com")).isFalse();
    }

    @Test
    @DisplayName("succeeding clears the record")
    void successResetsTheCount() {
        // Otherwise somebody who mistyped four times and then remembered stays one typo away from
        // a lockout for the rest of the hour.
        LoginAttemptLimiter limiter = new LoginAttemptLimiter();
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("creator@example.com");
        }

        limiter.recordSuccess("creator@example.com");
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("creator@example.com");
        }

        assertThat(limiter.allow("creator@example.com")).isTrue();
    }

    @Test
    @DisplayName("locking one address does not lock anybody else")
    void lockoutIsPerAddress() {
        // Keyed on the account being attacked rather than on the caller, so guesses spread across
        // addresses do not defeat it -- and so one creator's lockout is not everyone's outage.
        LoginAttemptLimiter limiter = new LoginAttemptLimiter();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("victim@example.com");
        }

        assertThat(limiter.allow("victim@example.com")).isFalse();
        assertThat(limiter.allow("someone-else@example.com")).isTrue();
    }

    @Test
    @DisplayName("the address is normalised, so casing does not buy extra attempts")
    void normalisesTheKey() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter();
        limiter.recordFailure("Creator@Example.com");
        limiter.recordFailure(" creator@example.com ");
        limiter.recordFailure("CREATOR@EXAMPLE.COM");
        limiter.recordFailure("creator@example.com");
        limiter.recordFailure("cReAtOr@eXaMpLe.CoM");

        assertThat(limiter.allow("creator@example.com"))
                .as("varying the casing must not reset the counter")
                .isFalse();
    }

    @Test
    @DisplayName("an address nobody has tried is allowed")
    void unknownAddressIsAllowed() {
        assertThat(new LoginAttemptLimiter().allow("nobody@example.com")).isTrue();
    }
}
