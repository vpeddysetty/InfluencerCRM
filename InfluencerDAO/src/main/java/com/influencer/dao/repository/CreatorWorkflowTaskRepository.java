package com.influencer.dao.repository;

import com.influencer.dao.model.CreatorWorkflowTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreatorWorkflowTaskRepository extends JpaRepository<CreatorWorkflowTask, UUID> {
    List<CreatorWorkflowTask> findByUserId(UUID userId);
    List<CreatorWorkflowTask> findByCampaignCreatorId(UUID campaignCreatorId);
    List<CreatorWorkflowTask> findByUserIdAndCampaignCreatorId(UUID userId, UUID campaignCreatorId);
}
