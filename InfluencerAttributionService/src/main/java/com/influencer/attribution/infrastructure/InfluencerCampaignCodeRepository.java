package com.influencer.attribution.infrastructure;

import com.influencer.attribution.domain.InfluencerCampaignCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InfluencerCampaignCodeRepository extends JpaRepository<InfluencerCampaignCode, UUID> {
    List<InfluencerCampaignCode> findByUserId(UUID userId);

    List<InfluencerCampaignCode> findByBrandId(UUID brandId);
    List<InfluencerCampaignCode> findByCampaignId(UUID campaignId);
    List<InfluencerCampaignCode> findByCreatorId(UUID creatorId);
    List<InfluencerCampaignCode> findByUserIdAndCampaignId(UUID userId, UUID campaignId);

    List<InfluencerCampaignCode> findByBrandIdAndCampaignId(UUID brandId, UUID campaignId);
    Optional<InfluencerCampaignCode> findByUserIdAndCode(UUID userId, String code);

    Optional<InfluencerCampaignCode> findByBrandIdAndCode(UUID brandId, String code);
}
