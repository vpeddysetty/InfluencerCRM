package com.influencer.dao.repository;

import com.influencer.dao.model.CampaignCreator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignCreatorRepository extends JpaRepository<CampaignCreator, UUID> {
	Optional<CampaignCreator> findByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);
}
