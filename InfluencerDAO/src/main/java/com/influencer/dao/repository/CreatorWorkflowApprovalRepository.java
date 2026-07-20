package com.influencer.dao.repository;

import com.influencer.dao.model.CreatorWorkflowApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreatorWorkflowApprovalRepository extends JpaRepository<CreatorWorkflowApproval, UUID> {
    List<CreatorWorkflowApproval> findByUserIdOrderBySubmittedAtDesc(UUID userId);
    List<CreatorWorkflowApproval> findByCampaignCreatorIdOrderByReviewRoundDesc(UUID campaignCreatorId);
    List<CreatorWorkflowApproval> findByUserIdAndCampaignCreatorIdOrderByReviewRoundDesc(UUID userId, UUID campaignCreatorId);
}
