package com.influencer.dao.workflow.infrastructure;

import com.influencer.dao.workflow.domain.WorkflowBoardStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowBoardStageRepository extends JpaRepository<WorkflowBoardStage, UUID> {
    List<WorkflowBoardStage> findByUserIdOrderByPositionAsc(UUID userId);

    List<WorkflowBoardStage> findByBrandIdOrderByPositionAsc(UUID brandId);
    List<WorkflowBoardStage> findByBoardIdOrderByPositionAsc(UUID boardId);
    List<WorkflowBoardStage> findByUserIdAndBoardIdOrderByPositionAsc(UUID userId, UUID boardId);

    List<WorkflowBoardStage> findByBrandIdAndBoardIdOrderByPositionAsc(UUID brandId, UUID boardId);
    void deleteByBoardId(UUID boardId);
}
