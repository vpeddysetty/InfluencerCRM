package com.influencer.creator.infrastructure;

import com.influencer.creator.domain.HealthThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HealthThresholdRepository extends JpaRepository<HealthThreshold, UUID> {
    Optional<HealthThreshold> findByBrandId(UUID brandId);
}
