package com.influencer.campaign.infrastructure;

import com.influencer.campaign.domain.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
	Optional<Campaign> findByUserIdAndName(UUID userId, String name);

	Optional<Campaign> findByBrandIdAndName(UUID brandId, String name);
	List<Campaign> findByUserId(UUID userId);

	List<Campaign> findByBrandId(UUID brandId);
	List<Campaign> findByUserIdAndCampaignType(UUID userId, String campaignType);

	List<Campaign> findByBrandIdAndCampaignType(UUID brandId, String campaignType);
}
