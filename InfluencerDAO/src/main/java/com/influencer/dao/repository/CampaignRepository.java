package com.influencer.dao.repository;

import com.influencer.dao.model.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
	Optional<Campaign> findByUserIdAndName(UUID userId, String name);
}
