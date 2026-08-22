package com.influencer.webe.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Whether an address has been proven, and whether that blocks sign-in.
 *
 * <p>Pure and static so every rule here can be tested without a database, a mail server or a web
 * request — the same reason {@link SubscriptionState} and {@code LandingStageMachine} exist. Each
 * of these decisions fails silently when it is wrong: a rule that is too strict locks a paying
 * customer out of an account they own, and one that is too loose lets someone hold an account on
 * an address they never controlled.
 *
 * <h2>Why federated sign-ins are exempt</h2>
 *
 * <p>Google and Facebook have already proven the address, and
 * {@code identity.federated_identities.email_verified_by_idp} records whether they actually
 * asserted it rather than assuming they did — {@code FederatedIdentity.isTrustworthy()} refuses an
 * unverified assertion. Emailing a link to an address an IdP just confirmed adds a step to signup
 * and no security at all.
 *
 * <p>The exemption is expressed as a property of the <em>sign-in method</em>, not of the user. A
 * user who signs up with Google and later sets a password is verified because Google vouched for
 * the address, and nothing about adding a password makes that less true.
 *
 * <h2>Why NULL means verified</h2>
 *
 * <p>{@code users.email_verified_at} is nullable with no default, and null is treated as
 * grandfathered rather than unverified. Enforcement is on sign-in, so backfilling existing rows to
 * "unverified" would lock out every account that existed before this shipped, with no way to
 * distinguish them from genuine new signups afterwards. New password signups write an explicit
 * unverified marker instead; see {@link #isVerified}.
 */
public final class EmailVerificationPolicy {

    /**
     * How long a verification link lives.
     *
     * <p>Shorter than the 7-day member invitation deliberately. An invitation is a convenience
     * someone may act on next week; this is a security control on an account that already exists,
     * and a link sitting in an inbox is a credential. 24 hours is long enough to survive a signup
     * in the evening and a click the next morning.
     */
    public static final Duration TOKEN_TTL = Duration.ofHours(24);

    /**
     * Most emails one address may be sent for a single verification.
     *
     * <p>The resend endpoint cannot require authentication — the whole point is that the user
     * cannot sign in yet — so without a ceiling it is an open relay pointed at any address someone
     * cares to type. Five covers a genuine "it went to spam, try again" without making the endpoint
     * useful for harassment.
     */
    public static final int MAX_SENDS = 5;

    /**
     * Least time between two sends for the same verification.
     *
     * <p>Separate from {@link #MAX_SENDS} because they stop different things: the cap bounds total
     * volume, this bounds the rate. Without it, five mails can be triggered in one second.
     */
    public static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    /** Sign-in methods that prove the address by themselves. */
    private static final String PASSWORD = "password";

    private EmailVerificationPolicy() {
    }

    /**
     * Whether a sign-in method proves the address on its own.
     *
     * <p>Everything that is not a password is a federated provider, and reaching this code at all
     * means the provider's assertion was already accepted. Written as "not password" rather than a
     * list of known providers on purpose: adding a third IdP should not silently start demanding
     * email verification from its users because someone forgot to extend a list here.
     */
    public static boolean methodProvesEmail(String signInMethod) {
        String normalized = normalize(signInMethod);
        return !normalized.isEmpty() && !PASSWORD.equals(normalized);
    }

    /**
     * Whether a user's address counts as proven.
     *
     * @param emailVerifiedAt when it was proven, or null for an account predating verification
     */
    public static boolean isVerified(Instant emailVerifiedAt) {
        // Null is grandfathered — see the class header. This is the single place that decision is
        // made, so it cannot drift between call sites.
        return emailVerifiedAt != null;
    }

    /**
     * Whether sign-in should be refused.
     *
     * <p>Both arguments matter and neither is sufficient alone. A federated sign-in is never
     * blocked even when the stored timestamp is absent, because the provider is proving the address
     * on this very request. A password sign-in is blocked only when the account carries an explicit
     * unverified marker.
     */
    public static boolean blocksSignIn(String signInMethod, Instant emailVerifiedAt,
                                       boolean hasPendingVerification) {
        if (methodProvesEmail(signInMethod)) {
            return false;
        }
        if (isVerified(emailVerifiedAt)) {
            return false;
        }
        // The grandfathering rule, and the reason this is not simply !isVerified(): a null
        // timestamp is ambiguous on its own. It means "never verified", which is true both of an
        // account created before this feature and of one created five minutes ago. The pending row
        // is what separates them — signup creates one, and no historical account has one.
        return hasPendingVerification;
    }

    /** Whether an outstanding token may still be used. */
    public static boolean isTokenUsable(Instant expiresAt, Instant consumedAt) {
        if (consumedAt != null) {
            // Single-use. A forwarded confirmation email must not stay live.
            return false;
        }
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    /** Whether another verification email may be sent. */
    public static boolean canResend(int sendCount, Instant lastSentAt) {
        if (sendCount >= MAX_SENDS) {
            return false;
        }
        if (lastSentAt == null) {
            return true;
        }
        return !lastSentAt.plus(RESEND_COOLDOWN).isAfter(Instant.now());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
