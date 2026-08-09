package com.influencer.content.infrastructure;

import com.influencer.content.domain.LandingPageView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LandingPageViewRepository extends JpaRepository<LandingPageView, UUID> {
    List<LandingPageView> findByUserId(UUID userId);

    List<LandingPageView> findByBrandId(UUID brandId);
    List<LandingPageView> findByCampaignCodeId(UUID campaignCodeId);
}
