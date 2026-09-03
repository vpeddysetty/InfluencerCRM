package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.SharePost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Reads and writes creator post claims (roadmap PR-45, V50). Append-only: there is no update. */
public interface SharePostRepository extends JpaRepository<SharePost, UUID> {

    /** Newest first, matching the index in V50 — a brand wants the latest claim at the top. */
    List<SharePost> findByLandingTemplateIdOrderByCreatedAtDesc(UUID landingTemplateId);
}
