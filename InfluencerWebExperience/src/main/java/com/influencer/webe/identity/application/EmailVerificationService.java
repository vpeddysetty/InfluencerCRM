package com.influencer.webe.identity.application;

import com.influencer.webe.identity.infrastructure.DaoEmailVerificationClient;
import com.influencer.webe.shared.application.EmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, redeems and resends proof-of-address challenges.
 *
 * <p>The rules live in {@link EmailVerificationPolicy}, which is pure and tested on its own; this
 * class does the I/O those rules govern. {@link DaoEmailVerificationClient} does the persistence.
 *
 * <p><b>Enforcement is a separate switch from issuance.</b> {@code enforced} gates only whether an
 * unverified account is refused at sign-in. Challenges are created and emailed regardless, so the
 * whole path runs from the day it ships — a feature that is entirely inert until a flag flips is
 * one nobody has ever executed, which is how {@code trial_ends_at} became a column that lied.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final DaoEmailVerificationClient verifications;
    private final com.influencer.webe.identity.infrastructure.DaoUserClient users;
    private final EmailPort emailPort;
    private final String uiBaseUrl;
    private final boolean enforced;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(DaoEmailVerificationClient verifications,
                                    com.influencer.webe.identity.infrastructure.DaoUserClient users,
                                    EmailPort emailPort,
                                    @Value("${web-experience.ui-base-url}") String uiBaseUrl,
                                    @Value("${web-experience.email-verification.enforced:false}")
                                    boolean enforced) {
        this.verifications = verifications;
        this.users = users;
        this.emailPort = emailPort;
        this.uiBaseUrl = uiBaseUrl == null ? "" : uiBaseUrl.replaceAll("/+$", "");
        this.enforced = enforced;
        if (!enforced) {
            log.info("[email-verification] NOT enforced. Challenges are still created and emailed, "
                    + "but an unverified account can sign in. Set "
                    + "web-experience.email-verification.enforced=true once delivery is proven.");
        }
    }

    /** Whether an unverified account is actually refused at sign-in. */
    public boolean isEnforced() {
        return enforced;
    }

    /**
     * Issues a challenge and emails it. Never throws on a delivery failure.
     *
     * <p>A signup that already created the user must not fail because SES was briefly unreachable —
     * that would leave an account with no way in and no way to ask for another link. The row is
     * written first, so a failed send is recoverable by resending; a failed write is not, and does
     * propagate.
     *
     * @return whether the email was actually handed to the mail provider
     */
    public boolean issue(UUID userId, String email) {
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(EmailVerificationPolicy.TOKEN_TTL);
        verifications.create(userId, email, hash(token), expiresAt);
        return send(email, token);
    }

    /**
     * Redeems a token.
     *
     * <p>Every failure mode returns the same message on purpose. Distinguishing "no such token"
     * from "expired" from "already used" tells whoever is probing which guesses were close, and the
     * legitimate user's next step is identical in all three cases: sign in and ask for another.
     */
    public void verify(String token) {
        if (token == null || token.isBlank()) {
            throw invalid();
        }
        DaoEmailVerificationClient.VerificationRecord record =
                verifications.findByToken(hash(token)).orElseThrow(EmailVerificationService::invalid);

        if (!EmailVerificationPolicy.isTokenUsable(record.expiresAt(), record.consumedAt())) {
            throw invalid();
        }
        verifications.consume(record.id());
        log.info("[email-verification] user {} proved {}", record.userId(), record.email());
    }

    /**
     * Sends a fresh link for an outstanding challenge.
     *
     * <p>Rotates the token, so the previous email's link stops working. A resend usually means the
     * first went astray, and leaving that one live is the opposite of what was asked for.
     *
     * <p>Reports success even when there is nothing to resend. The endpoint is necessarily
     * unauthenticated — the user cannot sign in yet — so answering differently for a real account
     * than for an unknown one turns it into an oracle for which addresses have accounts.
     */
    public void resend(UUID userId, String email) {
        Optional<DaoEmailVerificationClient.VerificationRecord> found = verifications.findCurrent(userId);
        if (found.isEmpty()) {
            return;
        }
        DaoEmailVerificationClient.VerificationRecord record = found.get();

        if (!EmailVerificationPolicy.canResend(record.sendCount(), record.lastSentAt())) {
            // Silent for the same reason: a distinguishable "you are rate limited" still confirms
            // the address exists.
            log.info("[email-verification] resend refused for user {} (count={}, last={})",
                    userId, record.sendCount(), record.lastSentAt());
            return;
        }

        String token = generateToken();
        Instant expiresAt = Instant.now().plus(EmailVerificationPolicy.TOKEN_TTL);
        verifications.recordSend(record.id(), hash(token), expiresAt);
        send(email == null || email.isBlank() ? record.email() : email, token);
    }

    /**
     * Resends by address, for a caller that has no user id — which is every caller of the public
     * endpoint, since the holder cannot sign in to tell us who they are.
     *
     * <p>Returns silently for an unknown address rather than reporting it. See {@link #resend}.
     */
    public void resendByEmail(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        users.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT))
                .ifPresent(user -> resend(user.id(), user.email()));
    }

    /** Stamps a federated user proven — the IdP asserted the address, so there is no token. */
    public void markVerifiedByProvider(UUID userId) {
        verifications.markVerified(userId);
    }

    /**
     * Whether this sign-in should be refused.
     *
     * <p>Returns false whenever enforcement is off, so the flag is a true kill switch regardless of
     * what the data says.
     */
    public boolean blocksSignIn(String signInMethod, Instant emailVerifiedAt, UUID userId) {
        if (!enforced) {
            return false;
        }
        if (EmailVerificationPolicy.methodProvesEmail(signInMethod)) {
            // Cheap exit before any DAO call: a provider is vouching for the address right now.
            return false;
        }
        if (EmailVerificationPolicy.isVerified(emailVerifiedAt)) {
            return false;
        }
        return EmailVerificationPolicy.blocksSignIn(signInMethod, emailVerifiedAt,
                verifications.hasPendingVerification(userId));
    }

    private boolean send(String email, String token) {
        String verifyUrl = uiBaseUrl + "/verify-email?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        try {
            EmailPort.Result result = emailPort.send(VerificationEmail.compose(
                    email, verifyUrl, EmailVerificationPolicy.TOKEN_TTL.toHours()));
            if (!result.sent()) {
                log.error("[email-verification] {} did not send to {}: {}",
                        result.provider(), email, result.detail());
            }
            return result.sent();
        } catch (RuntimeException exception) {
            // Logged, not rethrown — see issue().
            log.error("[email-verification] could not send to {}: {}", email, exception.toString());
            return false;
        }
    }

    /**
     * 256 bits from {@link SecureRandom}, URL-safe and unpadded.
     *
     * <p>Same shape as the member-invitation token. It appears in a URL, so padding characters
     * would be percent-encoded by some clients and not others, producing tokens that fail to
     * redeem depending on the mail client.
     */
    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "That verification link is not valid. Sign in to request a new one.");
    }
}
