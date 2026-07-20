package com.influencer.dao.repository;

import com.influencer.dao.model.CreatorWorkflowPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreatorWorkflowPaymentRepository extends JpaRepository<CreatorWorkflowPayment, UUID> {
    List<CreatorWorkflowPayment> findByUserId(UUID userId);
    List<CreatorWorkflowPayment> findByCampaignCreatorId(UUID campaignCreatorId);
    List<CreatorWorkflowPayment> findByUserIdAndCampaignCreatorId(UUID userId, UUID campaignCreatorId);
}
