package com.influencer.dao.shared.infrastructure;

import com.influencer.dao.shared.domain.AiGenerationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AiGenerationEventRepository extends JpaRepository<AiGenerationEvent, UUID> {

    /**
     * Calls by this account since {@code from} that count against the allowance.
     *
     * <p>Excludes {@code template}, which costs nothing: the allowance caps spend, and a fallback
     * the user did not choose must not consume it. Matches the partial index in V48, so the
     * predicate and the index cannot drift apart.
     *
     * <p>Also excludes {@code column_mapping} (PR-62), which is billed but deliberately not charged.
     * The spreadsheet import sends only the column HEADERS, so a 10,000-row roster and a 10-row one
     * cost one call each — it is bounded by the number of imports, never by their size. The free
     * tier's twenty was chosen as "far more than authoring a campaign in good faith takes",
     * measured against page drafts; a budget silently shared with imports would make twenty stop
     * meaning twenty drafts and bite during the activation the feature exists to produce. Those
     * rows stay in the table, and in the index, because "what did this account actually spend" is a
     * different question from "what counts against the ceiling" and both are worth answering.
     */
    @Query("""
            select count(e) from AiGenerationEvent e
             where e.accountId = :accountId
               and e.generator <> 'template'
               and e.kind <> 'column_mapping'
               and e.createdAt >= :from
            """)
    long countBilledSince(@Param("accountId") UUID accountId, @Param("from") Instant from);
}
