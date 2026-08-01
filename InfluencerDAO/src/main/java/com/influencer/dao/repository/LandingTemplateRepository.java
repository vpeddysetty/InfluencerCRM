package com.influencer.dao.repository;

import com.influencer.dao.model.LandingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandingTemplateRepository extends JpaRepository<LandingTemplate, UUID> {
    List<LandingTemplate> findByUserId(UUID userId);
    List<LandingTemplate> findByCampaignId(UUID campaignId);
    Optional<LandingTemplate> findByUserIdAndCampaignId(UUID userId, UUID campaignId);
    Optional<LandingTemplate> findByPublicSlug(String publicSlug);
}
