package com.influencer.dao.attribution.infrastructure;

import com.influencer.dao.attribution.domain.InfluencerSaleAttribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InfluencerSaleAttributionRepository extends JpaRepository<InfluencerSaleAttribution, UUID> {
    List<InfluencerSaleAttribution> findByUserId(UUID userId);

    List<InfluencerSaleAttribution> findByBrandId(UUID brandId);
    List<InfluencerSaleAttribution> findByCampaignCodeId(UUID campaignCodeId);
    List<InfluencerSaleAttribution> findByCampaignCreatorId(UUID campaignCreatorId);
    List<InfluencerSaleAttribution> findByUserIdAndCampaignCreatorId(UUID userId, UUID campaignCreatorId);

    List<InfluencerSaleAttribution> findByBrandIdAndCampaignCreatorId(UUID brandId, UUID campaignCreatorId);

    /**
     * One brand's attributions inside a half-open window (roadmap OP-39).
     *
     * <p><b>Why this exists.</b> The analytics path used to fetch every attribution a brand had ever
     * had and filter by date in Java, because this repository offered no way to narrow it. That is
     * O(all rows ever) per dashboard render, degrading as a customer uses the product -- the one
     * performance curve that punishes the most engaged account.
     *
     * <p><b>Half-open, {@code >= from} and {@code < until}.</b> The caller passes the start of the
     * day AFTER the inclusive end date, so a range ending "31 August" includes everything that
     * happened during the 31st rather than only the instant at midnight. Closing the upper bound
     * would silently drop a day's sales from every report that used it.
     *
     * <p>Served by {@code idx_isa_brand_occurred} (V54) -- equality on brand first, range on
     * occurred_at second, which is the order that lets one ordered scan return exactly these rows.
     */
    List<InfluencerSaleAttribution> findByBrandIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            UUID brandId, java.time.Instant from, java.time.Instant until);
}
