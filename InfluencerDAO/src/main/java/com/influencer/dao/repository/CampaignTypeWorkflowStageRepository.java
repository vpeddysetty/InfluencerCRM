package com.influencer.dao.repository;

import com.influencer.dao.model.CampaignTypeWorkflowStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CampaignTypeWorkflowStageRepository extends JpaRepository<CampaignTypeWorkflowStage, UUID> {
    List<CampaignTypeWorkflowStage> findByUserIdOrderByCampaignTypeAscPositionAsc(UUID userId);
    List<CampaignTypeWorkflowStage> findByUserIdAndCampaignTypeOrderByPositionAsc(UUID userId, String campaignType);
    void deleteByUserIdAndCampaignType(UUID userId, String campaignType);
}
