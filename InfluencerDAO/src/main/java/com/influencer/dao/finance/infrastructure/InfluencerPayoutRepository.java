package com.influencer.dao.finance.infrastructure;

import com.influencer.dao.finance.domain.InfluencerPayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InfluencerPayoutRepository extends JpaRepository<InfluencerPayout, UUID> {
    List<InfluencerPayout> findByUserId(UUID userId);

    List<InfluencerPayout> findByBrandId(UUID brandId);
    List<InfluencerPayout> findByUserIdAndStatus(UUID userId, String status);

    List<InfluencerPayout> findByBrandIdAndStatus(UUID brandId, String status);
    List<InfluencerPayout> findByCreatorId(UUID creatorId);
    List<InfluencerPayout> findByUserIdAndCreatorId(UUID userId, UUID creatorId);

    List<InfluencerPayout> findByBrandIdAndCreatorId(UUID brandId, UUID creatorId);
}
