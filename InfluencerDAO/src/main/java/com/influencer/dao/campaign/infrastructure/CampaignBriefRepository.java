package com.influencer.dao.campaign.infrastructure;

import com.influencer.dao.campaign.domain.CampaignBrief;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignBriefRepository extends JpaRepository<CampaignBrief, UUID> {
    List<CampaignBrief> findByUserId(UUID userId);

    List<CampaignBrief> findByBrandId(UUID brandId);
    List<CampaignBrief> findByCampaignId(UUID campaignId);
    Optional<CampaignBrief> findByUserIdAndCampaignId(UUID userId, UUID campaignId);

    Optional<CampaignBrief> findByBrandIdAndCampaignId(UUID brandId, UUID campaignId);
}
