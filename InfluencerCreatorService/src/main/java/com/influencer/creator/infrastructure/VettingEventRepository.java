package com.influencer.creator.infrastructure;

import com.influencer.creator.domain.VettingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VettingEventRepository extends JpaRepository<VettingEvent, UUID> {
    List<VettingEvent> findByCreatorIdOrderByOccurredAtDesc(UUID creatorId);
    List<VettingEvent> findByBrandIdOrderByOccurredAtDesc(UUID brandId);
}
