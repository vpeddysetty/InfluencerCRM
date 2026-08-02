package com.influencer.workflow.infrastructure;

import com.influencer.workflow.domain.WorkflowBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowBoardRepository extends JpaRepository<WorkflowBoard, UUID> {
    List<WorkflowBoard> findByUserIdOrderByPositionAscCreatedAtAsc(UUID userId);

    List<WorkflowBoard> findByBrandIdOrderByPositionAscCreatedAtAsc(UUID brandId);
    long countByUserId(UUID userId);

    long countByBrandId(UUID brandId);
    List<WorkflowBoard> findByUserIdAndIsActiveTrue(UUID userId);

    List<WorkflowBoard> findByBrandIdAndIsActiveTrue(UUID brandId);
}
