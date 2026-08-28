package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.PageHandoff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The audit trail of page handoffs (PR-40). */
@Repository
public interface PageHandoffRepository extends JpaRepository<PageHandoff, UUID> {

    List<PageHandoff> findByLandingTemplateIdOrderByCreatedAtDesc(UUID landingTemplateId);

    /** Absorbs a retry: the unique index makes a second insert with the same key an error. */
    Optional<PageHandoff> findByIdempotencyKey(String idempotencyKey);
}
