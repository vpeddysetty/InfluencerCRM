package com.influencer.dao.creator.infrastructure;

import com.influencer.dao.creator.domain.CampaignCreator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignCreatorRepository extends JpaRepository<CampaignCreator, UUID> {
	Optional<CampaignCreator> findByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);
	List<CampaignCreator> findByUserId(UUID userId);

	List<CampaignCreator> findByBrandId(UUID brandId);

	/**
	 * Engagements whose content licence lapses inside a window (roadmap PR-68).
	 *
	 * <p><b>The useful half of rights tracking.</b> Recording the terms is bookkeeping; knowing what
	 * expires in the next 30 days is what stops a brand still running an ad it no longer has the
	 * right to run -- and it doubles as a renewal prompt, which is where an agency's next month of
	 * revenue comes from.
	 *
	 * <p>Only rows with an end date can expire. A perpetual grant is a start with no end, and an
	 * unrecorded one is neither -- both are correctly absent here rather than surfacing as
	 * something to chase. Served by {@code idx_campaign_creators_rights_expiry} (V55), which is
	 * partial on exactly that predicate.
	 */
	@Query(value = """
			select * from campaign_creators
			 where brand_id = :brandId
			   and rights_end_at is not null
			   and rights_end_at >= :from
			   and rights_end_at < :until
			 order by rights_end_at asc
			""", nativeQuery = true)
	List<CampaignCreator> findExpiringRights(@Param("brandId") UUID brandId,
	                                         @Param("from") java.time.Instant from,
	                                         @Param("until") java.time.Instant until);
}
