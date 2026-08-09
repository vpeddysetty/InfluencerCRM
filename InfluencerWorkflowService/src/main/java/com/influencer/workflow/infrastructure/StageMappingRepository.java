package com.influencer.workflow.infrastructure;

import com.influencer.workflow.domain.StageMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StageMappingRepository extends JpaRepository<StageMapping, UUID> {
    List<StageMapping> findByBoardId(UUID boardId);
    List<StageMapping> findByBrandId(UUID brandId);
    Optional<StageMapping> findByBoardIdAndPageStage(UUID boardId, String pageStage);
}
