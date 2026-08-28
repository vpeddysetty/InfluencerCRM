package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.CreatorPortalSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** Creator portal sessions, keyed by the SHA-256 hash of the token (PR-40). */
@Repository
public interface CreatorPortalSessionRepository extends JpaRepository<CreatorPortalSession, String> {

    /**
     * Revoke one session — sign-out.
     *
     * <p>Marks the row rather than deleting it, so an incident review can still distinguish a
     * deliberate sign-out from an expiry. Idempotent: revoking an already-revoked session is not
     * an error, because a retry after a partial failure must be able to finish.
     */
    @Modifying
    @Query("update CreatorPortalSession s set s.revokedAt = :now "
            + "where s.tokenHash = :tokenHash and s.revokedAt is null")
    int revoke(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /**
     * Revoke every live session for one creator.
     *
     * <p>Not called yet, and deliberately here anyway: it is what a password change or a suspected
     * compromise needs, and its absence is the gap that makes a long-lived bearer token dangerous.
     * {@code RefreshToken} carries the same method for the same reason.
     */
    @Modifying
    @Query("update CreatorPortalSession s set s.revokedAt = :now "
            + "where s.creatorIdentityId = :creatorIdentityId and s.revokedAt is null")
    int revokeAllForCreator(@Param("creatorIdentityId") java.util.UUID creatorIdentityId,
                            @Param("now") Instant now);

    /**
     * Housekeeping: drop sessions that expired more than a grace period ago.
     *
     * <p>Deletes rather than marks, because an expired session answers no forensic question a
     * revoked one does not — and this table would otherwise grow without limit.
     */
    @Modifying
    @Query("delete from CreatorPortalSession s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
