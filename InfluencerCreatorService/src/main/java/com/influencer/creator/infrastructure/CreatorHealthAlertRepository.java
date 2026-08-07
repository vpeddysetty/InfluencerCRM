package com.influencer.creator.infrastructure;

import com.influencer.creator.domain.CreatorHealthAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorHealthAlertRepository extends JpaRepository<CreatorHealthAlert, UUID> {
    List<CreatorHealthAlert> findByBrandIdOrderByCreatedAtDesc(UUID brandId);
    List<CreatorHealthAlert> findByBrandIdAndStatusOrderByCreatedAtDesc(UUID brandId, String status);
    List<CreatorHealthAlert> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId);

    /**
     * The open alert of this type for this creator, if any.
     *
     * Used to avoid re-raising the same warning on every refresh — the partial unique index
     * enforces it, and this is how the service checks before inserting rather than relying on a
     * constraint violation.
     */
    Optional<CreatorHealthAlert> findByCreatorIdAndAlertTypeAndStatus(
            UUID creatorId, String alertType, String status);
}
