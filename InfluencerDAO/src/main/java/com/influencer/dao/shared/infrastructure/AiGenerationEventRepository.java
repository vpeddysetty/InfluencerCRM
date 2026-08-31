package com.influencer.dao.shared.infrastructure;

import com.influencer.dao.shared.domain.AiGenerationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AiGenerationEventRepository extends JpaRepository<AiGenerationEvent, UUID> {

    /**
     * Billed calls by this account since {@code from}.
     *
     * <p>Excludes {@code template}, which costs nothing: the allowance caps spend, and a fallback
     * the user did not choose must not consume it. Matches the partial index in V48, so the
     * predicate and the index cannot drift apart.
     */
    @Query("""
            select count(e) from AiGenerationEvent e
             where e.accountId = :accountId
               and e.generator <> 'template'
               and e.createdAt >= :from
            """)
    long countBilledSince(@Param("accountId") UUID accountId, @Param("from") Instant from);
}
