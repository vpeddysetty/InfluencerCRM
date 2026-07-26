package com.influencer.dao.repository;

import com.influencer.dao.model.WorkflowBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowBoardRepository extends JpaRepository<WorkflowBoard, UUID> {
    List<WorkflowBoard> findByUserIdOrderByPositionAscCreatedAtAsc(UUID userId);
    long countByUserId(UUID userId);
    List<WorkflowBoard> findByUserIdAndIsActiveTrue(UUID userId);
}
