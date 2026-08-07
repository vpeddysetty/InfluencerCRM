package com.influencer.workflow.infrastructure;

import com.influencer.workflow.domain.StageTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StageTransitionRepository extends JpaRepository<StageTransition, UUID> {
    List<StageTransition> findByLandingTemplateIdOrderByOccurredAtDesc(UUID landingTemplateId);

    /** Idempotency check: a key that already exists means this transition was already applied. */
    Optional<StageTransition> findByIdempotencyKey(String idempotencyKey);
}
