package com.influencer.dao.finance.infrastructure;

import com.influencer.dao.finance.domain.InfluencerPayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
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

    /**
     * What this brand has actually PAID this creator between two instants (roadmap PR-49).
     *
     * <p>Only {@code status = 'paid'} counts. A draft or a failed payout is not money anyone
     * received, and counting it would withhold payment over a threshold that was never crossed --
     * the precise failure this arithmetic exists to avoid.
     *
     * <p>Per creator PER BRAND, because the obligation follows the payer: two brands paying the same
     * person $400 each have each paid under the threshold, and this product is not a payment
     * aggregator. `creators` is already one row per (creator, brand), so this matches that grain.
     *
     * <p>Returns {@code null} when there are no matching rows -- coalesced by the caller rather than
     * here, so "no payouts" and "zero paid" stay distinguishable at the boundary.
     */
    @Query("""
            select sum(p.totalAmount) from InfluencerPayout p
             where p.creatorId = :creatorId
               and p.brandId = :brandId
               and p.status = 'paid'
               and p.paidAt >= :from
               and p.paidAt < :until
            """)
    java.math.BigDecimal sumPaidBetween(@Param("creatorId") UUID creatorId,
                                        @Param("brandId") UUID brandId,
                                        @Param("from") java.time.Instant from,
                                        @Param("until") java.time.Instant until);

    List<InfluencerPayout> findByBrandIdAndCreatorId(UUID brandId, UUID creatorId);
}
