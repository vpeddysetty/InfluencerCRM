package com.influencer.dao.repository;

import com.influencer.dao.model.InfluencerCommission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InfluencerCommissionRepository extends JpaRepository<InfluencerCommission, UUID> {
    List<InfluencerCommission> findByUserId(UUID userId);
    List<InfluencerCommission> findByUserIdAndStatus(UUID userId, String status);
    List<InfluencerCommission> findByCreatorId(UUID creatorId);
    List<InfluencerCommission> findByUserIdAndCreatorId(UUID userId, UUID creatorId);
    List<InfluencerCommission> findByPayoutId(UUID payoutId);
}
