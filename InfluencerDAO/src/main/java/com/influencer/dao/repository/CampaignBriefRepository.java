package com.influencer.dao.repository;

import com.influencer.dao.model.CampaignBrief;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignBriefRepository extends JpaRepository<CampaignBrief, UUID> {
    List<CampaignBrief> findByUserId(UUID userId);
    List<CampaignBrief> findByCampaignId(UUID campaignId);
    Optional<CampaignBrief> findByUserIdAndCampaignId(UUID userId, UUID campaignId);
}
