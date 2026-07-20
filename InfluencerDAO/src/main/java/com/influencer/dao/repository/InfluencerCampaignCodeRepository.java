package com.influencer.dao.repository;

import com.influencer.dao.model.InfluencerCampaignCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InfluencerCampaignCodeRepository extends JpaRepository<InfluencerCampaignCode, UUID> {
    List<InfluencerCampaignCode> findByUserId(UUID userId);
    List<InfluencerCampaignCode> findByCampaignId(UUID campaignId);
    List<InfluencerCampaignCode> findByCreatorId(UUID creatorId);
    List<InfluencerCampaignCode> findByUserIdAndCampaignId(UUID userId, UUID campaignId);
    Optional<InfluencerCampaignCode> findByUserIdAndCode(UUID userId, String code);
}
