package com.influencer.dao.creator.infrastructure;

import com.influencer.dao.creator.domain.CreatorQualityReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreatorQualityReportRepository extends JpaRepository<CreatorQualityReport, UUID> {
    List<CreatorQualityReport> findByBrandIdOrderByCreatedAtDesc(UUID brandId);
    List<CreatorQualityReport> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId);

    /**
     * The vendor trigger (group2-build-vs-buy.md 5.1): three complaints in a quarter, or one on
     * a creator our own signal rated clean. Counting them is what makes "wait for complaints" a
     * real threshold rather than someone half-remembering that brands grumbled.
     */
    long countByBrandIdAndStatus(UUID brandId, String status);
}
