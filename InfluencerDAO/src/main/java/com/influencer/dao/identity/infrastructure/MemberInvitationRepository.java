package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.MemberInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemberInvitationRepository extends JpaRepository<MemberInvitation, UUID> {

    Optional<MemberInvitation> findByTokenHash(String tokenHash);

    List<MemberInvitation> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    Optional<MemberInvitation> findByAccountIdAndEmailAndStatus(UUID accountId, String email, String status);

    /**
     * Inserts with an explicit cast to the {@code account_role} enum.
     *
     * <p>Hibernate binds the role as text and Postgres will not implicitly coerce it, the same
     * reason {@code MembershipRepository.upsertMembership} uses a native query.
     */
    @Modifying
    @Query(value = """
            insert into member_invitations
                (account_id, email, role, brand_id, token_hash, status, invited_by, expires_at)
            values
                (:accountId, :email, cast(:role as account_role), :brandId, :tokenHash,
                 'pending', :invitedBy, :expiresAt)
            """, nativeQuery = true)
    void insertInvitation(@Param("accountId") UUID accountId,
                          @Param("email") String email,
                          @Param("role") String role,
                          @Param("brandId") UUID brandId,
                          @Param("tokenHash") String tokenHash,
                          @Param("invitedBy") UUID invitedBy,
                          @Param("expiresAt") Instant expiresAt);
}
