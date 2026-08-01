package com.influencer.dao.repository;

import com.influencer.dao.model.LandingPageView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LandingPageViewRepository extends JpaRepository<LandingPageView, UUID> {
    List<LandingPageView> findByUserId(UUID userId);
    List<LandingPageView> findByCampaignCodeId(UUID campaignCodeId);
}
