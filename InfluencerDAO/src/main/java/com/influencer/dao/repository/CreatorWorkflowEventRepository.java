package com.influencer.dao.repository;

import com.influencer.dao.model.CreatorWorkflowEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreatorWorkflowEventRepository extends JpaRepository<CreatorWorkflowEvent, UUID> {
    List<CreatorWorkflowEvent> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<CreatorWorkflowEvent> findByCampaignCreatorIdOrderByCreatedAtDesc(UUID campaignCreatorId);
    List<CreatorWorkflowEvent> findByUserIdAndCampaignCreatorIdOrderByCreatedAtDesc(UUID userId, UUID campaignCreatorId);
}
