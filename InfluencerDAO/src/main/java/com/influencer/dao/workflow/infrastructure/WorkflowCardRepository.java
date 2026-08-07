package com.influencer.dao.workflow.infrastructure;

import com.influencer.dao.workflow.domain.WorkflowCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowCardRepository extends JpaRepository<WorkflowCard, UUID> {
    List<WorkflowCard> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<WorkflowCard> findByBrandIdOrderByCreatedAtDesc(UUID brandId);
    List<WorkflowCard> findByUserIdAndBoardIdOrderByPositionAsc(UUID userId, UUID boardId);

    List<WorkflowCard> findByBrandIdAndBoardIdOrderByPositionAsc(UUID brandId, UUID boardId);
    List<WorkflowCard> findByUserIdAndBoardIdIsNullOrderByCreatedAtDesc(UUID userId);

    List<WorkflowCard> findByBrandIdAndBoardIdIsNullOrderByCreatedAtDesc(UUID brandId);

    /** Cards tracking a landing page (Phase D) — how a stage change finds the card to move. */
    List<WorkflowCard> findByBrandIdAndLandingTemplateId(UUID brandId, UUID landingTemplateId);
}
