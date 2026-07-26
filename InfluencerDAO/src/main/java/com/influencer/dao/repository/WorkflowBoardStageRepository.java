package com.influencer.dao.repository;

import com.influencer.dao.model.WorkflowBoardStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowBoardStageRepository extends JpaRepository<WorkflowBoardStage, UUID> {
    List<WorkflowBoardStage> findByUserIdOrderByPositionAsc(UUID userId);
    List<WorkflowBoardStage> findByBoardIdOrderByPositionAsc(UUID boardId);
    List<WorkflowBoardStage> findByUserIdAndBoardIdOrderByPositionAsc(UUID userId, UUID boardId);
    void deleteByBoardId(UUID boardId);
}
