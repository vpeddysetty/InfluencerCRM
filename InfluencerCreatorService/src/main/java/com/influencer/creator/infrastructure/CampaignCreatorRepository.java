package com.influencer.creator.infrastructure;

import com.influencer.creator.domain.CampaignCreator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignCreatorRepository extends JpaRepository<CampaignCreator, UUID> {
	Optional<CampaignCreator> findByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);
	List<CampaignCreator> findByUserId(UUID userId);

	List<CampaignCreator> findByBrandId(UUID brandId);
}
