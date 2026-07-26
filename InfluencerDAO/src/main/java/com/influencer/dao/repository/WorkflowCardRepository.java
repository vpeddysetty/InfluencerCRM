package com.influencer.dao.repository;

import com.influencer.dao.model.WorkflowCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowCardRepository extends JpaRepository<WorkflowCard, UUID> {
    List<WorkflowCard> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<WorkflowCard> findByUserIdAndBoardIdOrderByPositionAsc(UUID userId, UUID boardId);
    List<WorkflowCard> findByUserIdAndBoardIdIsNullOrderByCreatedAtDesc(UUID userId);
}
