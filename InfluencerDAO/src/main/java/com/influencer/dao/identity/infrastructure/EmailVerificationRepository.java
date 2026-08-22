package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {

    /**
     * Looks a challenge up by the hash of the token presented.
     *
     * <p>The caller hashes what it was given and searches for that. The raw token is never stored,
     * so it is never compared — which also means a timing side-channel on this lookup reveals a
     * hash, not a token.
     */
    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /**
     * The live challenge for a user, if any.
     *
     * <p>Matches the partial unique index {@code uq_email_verifications_live}, which permits many
     * consumed rows per user and only one unconsumed one. Consumed rows are kept as history rather
     * than deleted, so "when did this account prove its address" stays answerable.
     */
    Optional<EmailVerification> findByUserIdAndConsumedAtIsNull(UUID userId);

    /** Whether the account is currently locked pending verification — the sign-in gate. */
    boolean existsByUserIdAndConsumedAtIsNull(UUID userId);
}
