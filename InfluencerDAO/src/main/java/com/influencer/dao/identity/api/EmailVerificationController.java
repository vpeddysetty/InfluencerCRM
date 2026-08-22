package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.EmailVerification;
import com.influencer.dao.identity.domain.User;
import com.influencer.dao.identity.infrastructure.EmailVerificationRepository;
import com.influencer.dao.identity.infrastructure.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Storage for proof-of-address challenges.
 *
 * <p>Policy lives in the BFF ({@code EmailVerificationPolicy}); this is persistence. The one rule
 * enforced here is the one that cannot be enforced anywhere else: {@link #consume} marks the
 * challenge used and stamps the user in a single transaction, so a crash between the two cannot
 * leave a consumed token on an account that is still locked.
 */
@RestController
@RequestMapping("/email-verifications")
public class EmailVerificationController {

    private final EmailVerificationRepository repository;
    private final UserRepository users;

    public EmailVerificationController(EmailVerificationRepository repository, UserRepository users) {
        this.repository = repository;
        this.users = users;
    }

    /** The live challenge for a user, or 404 when there is none. */
    @GetMapping
    public EmailVerification current(@RequestParam UUID userId) {
        return repository.findByUserIdAndConsumedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No pending verification"));
    }

    /** Whether this account is locked pending verification — the sign-in gate. */
    @GetMapping("/pending")
    public PendingResponse pending(@RequestParam UUID userId) {
        return new PendingResponse(repository.existsByUserIdAndConsumedAtIsNull(userId));
    }

    @PostMapping
    @Transactional
    public EmailVerification create(@RequestBody CreateRequest request) {
        // One live challenge per user, matching the partial unique index. Reissuing replaces the
        // outstanding one rather than adding a second: two live tokens for one account means the
        // older one stays usable after the user asked for a new one, which is exactly what a
        // resend is meant to invalidate.
        repository.findByUserIdAndConsumedAtIsNull(request.userId())
                .ifPresent(repository::delete);

        EmailVerification verification = new EmailVerification();
        verification.setUserId(request.userId());
        verification.setEmail(request.email());
        verification.setTokenHash(request.tokenHash());
        verification.setExpiresAt(request.expiresAt());
        verification.setSendCount(1);
        verification.setLastSentAt(Instant.now());
        verification.setCreatedAt(Instant.now());
        return repository.saveAndFlush(verification);
    }

    /** Looks up a challenge by token hash. 404 rather than null so a bad token cannot read as valid. */
    @GetMapping("/by-token")
    public EmailVerification byToken(@RequestParam String tokenHash) {
        return repository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such verification"));
    }

    /**
     * Marks a challenge used and the user proven, atomically.
     *
     * <p>Both writes or neither. Marking the token consumed without stamping the user would lock
     * the account permanently — the token is single-use, so there would be nothing left to redeem.
     *
     * <p>Re-consuming is refused rather than treated as idempotent. A second click on the same link
     * is harmless, but a second call for a token already consumed is how a leaked link stays usable,
     * and the caller can tell the difference from the 409.
     */
    @PostMapping("/{id}/consume")
    @Transactional
    public EmailVerification consume(@PathVariable UUID id) {
        EmailVerification verification = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such verification"));
        if (verification.getConsumedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification already consumed");
        }

        Instant now = Instant.now();
        verification.setConsumedAt(now);

        User user = users.findById(verification.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        user.setEmailVerifiedAt(now);
        users.save(user);

        return repository.saveAndFlush(verification);
    }

    /** Records that another email went out, for the resend cap and cooldown. */
    @PostMapping("/{id}/sent")
    @Transactional
    public EmailVerification recordSend(@PathVariable UUID id, @RequestBody RecordSendRequest request) {
        EmailVerification verification = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such verification"));
        verification.setSendCount(verification.getSendCount() + 1);
        verification.setLastSentAt(Instant.now());
        if (request != null && request.tokenHash() != null && !request.tokenHash().isBlank()) {
            // A resend issues a NEW token and invalidates the old one. Reusing the existing hash
            // would mean the first email's link still works, so a resend triggered because the
            // first went astray would leave that stray link live.
            verification.setTokenHash(request.tokenHash());
            verification.setExpiresAt(request.expiresAt());
        }
        return repository.saveAndFlush(verification);
    }

    /**
     * Marks a user's address proven without a challenge.
     *
     * <p>For federated signups only: the IdP asserted the address, so there is no token to redeem.
     */
    @PostMapping("/mark-verified")
    @Transactional
    public MarkVerifiedResponse markVerified(@RequestBody MarkVerifiedRequest request) {
        User user = users.findById(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
            users.save(user);
        }
        return new MarkVerifiedResponse(user.getId(), user.getEmailVerifiedAt());
    }

    public record CreateRequest(UUID userId, String email, String tokenHash, Instant expiresAt) {
    }

    public record RecordSendRequest(String tokenHash, Instant expiresAt) {
    }

    public record MarkVerifiedRequest(UUID userId) {
    }

    public record MarkVerifiedResponse(UUID userId, Instant emailVerifiedAt) {
    }

    public record PendingResponse(boolean pending) {
    }
}
