package com.influencer.dao.repository;

import com.influencer.dao.model.InfluencerSaleAttribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InfluencerSaleAttributionRepository extends JpaRepository<InfluencerSaleAttribution, UUID> {
    List<InfluencerSaleAttribution> findByUserId(UUID userId);
    List<InfluencerSaleAttribution> findByCampaignCodeId(UUID campaignCodeId);
    List<InfluencerSaleAttribution> findByCampaignCreatorId(UUID campaignCreatorId);
    List<InfluencerSaleAttribution> findByUserIdAndCampaignCreatorId(UUID userId, UUID campaignCreatorId);
}
