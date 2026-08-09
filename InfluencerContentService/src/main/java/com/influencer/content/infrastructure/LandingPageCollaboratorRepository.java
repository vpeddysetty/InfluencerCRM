package com.influencer.content.infrastructure;

import com.influencer.content.domain.LandingPageCollaborator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandingPageCollaboratorRepository extends JpaRepository<LandingPageCollaborator, UUID> {

    /** Active collaborators on a page. Revoked grants stay in the table but not in this list. */
    List<LandingPageCollaborator> findByLandingTemplateIdAndRevokedAtIsNull(UUID landingTemplateId);

    /** Every page a creator can currently reach — the portal's page list. */
    List<LandingPageCollaborator> findByCreatorIdentityIdAndRevokedAtIsNull(UUID creatorIdentityId);

    /** The access check on every creator-portal edit. */
    Optional<LandingPageCollaborator> findByLandingTemplateIdAndCreatorIdentityIdAndRevokedAtIsNull(
            UUID landingTemplateId, UUID creatorIdentityId);
}
