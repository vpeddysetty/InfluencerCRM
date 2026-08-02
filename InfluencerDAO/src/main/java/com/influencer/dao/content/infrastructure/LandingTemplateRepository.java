package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.LandingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandingTemplateRepository extends JpaRepository<LandingTemplate, UUID> {
    List<LandingTemplate> findByUserId(UUID userId);

    List<LandingTemplate> findByBrandId(UUID brandId);
    List<LandingTemplate> findByCampaignId(UUID campaignId);
    Optional<LandingTemplate> findByUserIdAndCampaignId(UUID userId, UUID campaignId);

    Optional<LandingTemplate> findByBrandIdAndCampaignId(UUID brandId, UUID campaignId);
    Optional<LandingTemplate> findByPublicSlug(String publicSlug);
}
