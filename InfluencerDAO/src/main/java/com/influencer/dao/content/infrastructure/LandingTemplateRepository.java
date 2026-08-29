package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.LandingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandingTemplateRepository extends JpaRepository<LandingTemplate, UUID> {
    List<LandingTemplate> findByUserId(UUID userId);

    List<LandingTemplate> findByBrandId(UUID brandId);
    List<LandingTemplate> findByCampaignId(UUID campaignId);
    Optional<LandingTemplate> findByUserIdAndCampaignId(UUID userId, UUID campaignId);

    Optional<LandingTemplate> findByBrandIdAndCampaignId(UUID brandId, UUID campaignId);
    Optional<LandingTemplate> findByPublicSlug(String publicSlug);

    /**
     * Pages that have been waiting on someone since before {@code before} (PR-44).
     *
     * <p>Drives the abandonment sweep. Filtered on {@code turn is not null} so the partial index
     * from {@code V45} is used and the vast majority of pages — which nobody owes anything on — are
     * never examined.
     *
     * <p>The reminder stamp is deliberately NOT filtered here. Whether a nudge is due depends on
     * comparing it against {@code turnChangedAt} AND on which threshold has passed, and pushing
     * that into SQL would put the escalation rule in two places. The candidate set is small enough
     * that the caller can decide.
     */
    @Query("select t from LandingTemplate t "
            + "where t.turn is not null and t.turnChangedAt is not null and t.turnChangedAt < :before")
    List<LandingTemplate> findAwaitingTurnSince(@Param("before") Instant before);
}
