package com.influencer.dao.creator.infrastructure;

import com.influencer.dao.creator.domain.CreatorMetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface CreatorMetricSnapshotRepository extends JpaRepository<CreatorMetricSnapshot, UUID> {
    /** The trend view (C3.6): the series, newest first. */
    List<CreatorMetricSnapshot> findByCreatorIdOrderByCapturedAtDesc(UUID creatorId);

    /** The baseline a drop is measured against: everything inside the comparison window. */
    List<CreatorMetricSnapshot> findByCreatorIdAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
            UUID creatorId, Instant capturedAt);
}
