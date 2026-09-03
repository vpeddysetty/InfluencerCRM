package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.PageLead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Reads and writes public-page leads (roadmap PR-61, V51). */
public interface PageLeadRepository extends JpaRepository<PageLead, UUID> {

    /** Newest first, matching the index in V51 — a brand wants the latest enquiry at the top. */
    List<PageLead> findByLandingTemplateIdOrderByCreatedAtDesc(UUID landingTemplateId);

    /**
     * Every lead for an address, for an erasure request.
     *
     * <p>Lower-cased on both sides because a person asking to be forgotten will not type their
     * address the way the form recorded it, and matching exactly would leave rows behind while
     * reporting success — the worst possible outcome for a deletion request. V51 carries the
     * matching functional index.
     */
    @Query("select l from PageLead l where lower(l.email) = lower(:email)")
    List<PageLead> findByEmailIgnoringCase(@Param("email") String email);
}
