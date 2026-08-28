package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.CreatorInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Creator invitations, looked up by token hash on redemption (PR-41). */
@Repository
public interface CreatorInviteRepository extends JpaRepository<CreatorInvite, UUID> {

    /**
     * The lookup redemption uses.
     *
     * <p>By HASH, never by token: the token is not stored, which is the whole point. Unique by
     * index, so this cannot return more than one.
     */
    Optional<CreatorInvite> findByTokenHash(String tokenHash);

    List<CreatorInvite> findByBrandIdAndStatusOrderByCreatedAtDesc(UUID brandId, String status);

    List<CreatorInvite> findByBrandIdOrderByCreatedAtDesc(UUID brandId);
}
