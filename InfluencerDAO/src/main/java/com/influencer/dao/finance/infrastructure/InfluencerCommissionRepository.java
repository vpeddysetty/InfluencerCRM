package com.influencer.dao.finance.infrastructure;

import com.influencer.dao.finance.domain.InfluencerCommission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InfluencerCommissionRepository extends JpaRepository<InfluencerCommission, UUID> {
    List<InfluencerCommission> findByUserId(UUID userId);

    List<InfluencerCommission> findByBrandId(UUID brandId);
    List<InfluencerCommission> findByUserIdAndStatus(UUID userId, String status);

    List<InfluencerCommission> findByBrandIdAndStatus(UUID brandId, String status);
    List<InfluencerCommission> findByCreatorId(UUID creatorId);
    List<InfluencerCommission> findByUserIdAndCreatorId(UUID userId, UUID creatorId);

    List<InfluencerCommission> findByBrandIdAndCreatorId(UUID brandId, UUID creatorId);
    List<InfluencerCommission> findByPayoutId(UUID payoutId);
}
