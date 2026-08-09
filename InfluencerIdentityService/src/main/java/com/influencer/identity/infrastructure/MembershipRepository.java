package com.influencer.identity.infrastructure;

import com.influencer.identity.domain.Membership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByAccountId(UUID accountId);

    List<Membership> findByUserId(UUID userId);

    Optional<Membership> findByAccountIdAndUserId(UUID accountId, UUID userId);

    /**
     * Inserts with an explicit cast to the {@code account_role} enum.
     *
     * <p>Hibernate binds the role as text, which Postgres will not implicitly coerce to an enum —
     * the same reason the creators query casts {@code platform_type}.
     */
    @Modifying
    @Query(value = """
            insert into memberships (account_id, user_id, role, status)
            values (:accountId, :userId, cast(:role as account_role), 'active')
            on conflict (account_id, user_id)
            do update set role = cast(:role as account_role), updated_at = now()
            """, nativeQuery = true)
    void upsertMembership(@Param("accountId") UUID accountId,
                          @Param("userId") UUID userId,
                          @Param("role") String role);
}
