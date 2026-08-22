package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide whether someone can get into their own account.
 *
 * <p>Every case here is one where being wrong is silent. Too strict and a paying customer is locked
 * out of an account they own with no error anyone sees; too loose and an account can be held on an
 * address its holder never controlled.
 */
class EmailVerificationPolicyTest {

    private static final Instant PROVEN = Instant.parse("2026-08-21T10:00:00Z");

    @Test
    @DisplayName("a federated sign-in is never blocked, verified column or not")
    void federatedIsExempt() {
        // The provider is proving the address on this very request, so the stored timestamp is
        // irrelevant. Demanding a click on a link Google just vouched for adds a step and no
        // security.
        assertFalse(EmailVerificationPolicy.blocksSignIn("google", null, true));
        assertFalse(EmailVerificationPolicy.blocksSignIn("facebook", null, true));
        assertFalse(EmailVerificationPolicy.blocksSignIn("google", PROVEN, false));
    }

    @Test
    @DisplayName("an unknown future provider is exempt without anyone editing this class")
    void unknownProviderIsExempt() {
        // Written as "not password" rather than a list of known providers on purpose. Adding a
        // third IdP must not silently start demanding email verification from its users because
        // someone forgot to extend a list here.
        assertTrue(EmailVerificationPolicy.methodProvesEmail("apple"));
        assertFalse(EmailVerificationPolicy.blocksSignIn("apple", null, true));
    }

    @Test
    @DisplayName("a password sign-in is blocked only while a verification is outstanding")
    void passwordBlockedWhenPending() {
        assertTrue(EmailVerificationPolicy.blocksSignIn("password", null, true));
        assertFalse(EmailVerificationPolicy.blocksSignIn("password", PROVEN, false));
    }

    @Test
    @DisplayName("accounts predating verification are grandfathered, not locked out")
    void existingAccountsAreNotLockedOut() {
        // THE trap in this whole feature. Enforcement is on sign-in, so treating a null timestamp
        // as "unverified" would lock out every account that existed before this shipped - and
        // afterwards there would be no way to tell those apart from genuine new signups. The
        // pending row is what distinguishes them: signup creates one, history has none.
        assertFalse(EmailVerificationPolicy.blocksSignIn("password", null, false));
    }

    @Test
    @DisplayName("a blank or null sign-in method is treated as a password, not as a provider")
    void unknownMethodFailsClosed() {
        // methodProvesEmail must not answer "yes" to an absent value: that would turn a missing
        // field anywhere upstream into a blanket exemption from the check.
        assertFalse(EmailVerificationPolicy.methodProvesEmail(null));
        assertFalse(EmailVerificationPolicy.methodProvesEmail(""));
        assertFalse(EmailVerificationPolicy.methodProvesEmail("   "));
        assertTrue(EmailVerificationPolicy.blocksSignIn(null, null, true));
    }

    @Test
    @DisplayName("the method name is matched case- and whitespace-insensitively")
    void methodIsNormalized() {
        assertFalse(EmailVerificationPolicy.methodProvesEmail("  Password "));
        assertFalse(EmailVerificationPolicy.methodProvesEmail("PASSWORD"));
        assertTrue(EmailVerificationPolicy.methodProvesEmail("  Google "));
    }

    @Test
    @DisplayName("a consumed token cannot verify a second time")
    void tokensAreSingleUse() {
        Instant future = Instant.now().plus(Duration.ofHours(1));
        assertTrue(EmailVerificationPolicy.isTokenUsable(future, null));
        // A forwarded confirmation email must not stay live.
        assertFalse(EmailVerificationPolicy.isTokenUsable(future, Instant.now()));
    }

    @Test
    @DisplayName("an expired token is refused, and a missing expiry is not treated as forever")
    void expiredTokensAreRefused() {
        assertFalse(EmailVerificationPolicy.isTokenUsable(Instant.now().minusSeconds(1), null));
        // Null expiry is a corrupt row, not an eternal token.
        assertFalse(EmailVerificationPolicy.isTokenUsable(null, null));
    }

    @Test
    @DisplayName("resending is bounded by both a total cap and a cooldown")
    void resendIsBounded() {
        Instant old = Instant.now().minus(Duration.ofHours(1));
        assertTrue(EmailVerificationPolicy.canResend(1, old));
        assertTrue(EmailVerificationPolicy.canResend(EmailVerificationPolicy.MAX_SENDS - 1, old));

        // The cap bounds total volume: the resend endpoint cannot require authentication - the
        // user cannot sign in yet - so without it, it is a relay pointed at any address typed.
        assertFalse(EmailVerificationPolicy.canResend(EmailVerificationPolicy.MAX_SENDS, old));

        // The cooldown bounds the rate. Without it the cap is reachable in one second.
        assertFalse(EmailVerificationPolicy.canResend(1, Instant.now()));
    }

    @Test
    @DisplayName("a token lives long enough to survive an overnight gap, and not a week")
    void tokenTtlIsBoundedOnBothSides() {
        // Long enough for signing up in the evening and clicking the next morning; short enough
        // that a link sitting in an inbox is not a standing credential. The member invitation's
        // 7 days is deliberately different - that is a convenience, this is a security control.
        assertTrue(EmailVerificationPolicy.TOKEN_TTL.compareTo(Duration.ofHours(12)) >= 0);
        assertTrue(EmailVerificationPolicy.TOKEN_TTL.compareTo(Duration.ofDays(2)) <= 0);
    }
}
