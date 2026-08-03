package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.CreatorIdentityLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorIdentityLinkRepository extends JpaRepository<CreatorIdentityLink, UUID> {

    List<CreatorIdentityLink> findByCreatorIdentityId(UUID creatorIdentityId);

    List<CreatorIdentityLink> findByCreatorIdentityIdAndStatus(UUID creatorIdentityId, String status);

    List<CreatorIdentityLink> findByBrandIdAndStatus(UUID brandId, String status);

    Optional<CreatorIdentityLink> findByCreatorIdentityIdAndCreatorId(UUID creatorIdentityId, UUID creatorId);

    /** A creator row may be confirmed to at most one identity — see the partial unique index. */
    Optional<CreatorIdentityLink> findByCreatorIdAndStatus(UUID creatorId, String status);
}
