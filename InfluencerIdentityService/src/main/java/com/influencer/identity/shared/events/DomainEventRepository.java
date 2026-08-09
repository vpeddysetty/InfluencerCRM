package com.influencer.identity.shared.events;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DomainEventRepository extends JpaRepository<DomainEvent, UUID> {

    /**
     * The relay's work queue: oldest pending events first, so consumers observe events in the order
     * they occurred.
     *
     * <p>{@code for update skip locked} lets several relay instances drain the outbox concurrently
     * without processing the same row twice and without blocking each other.
     */
    @Query(value = """
            select * from domain_events
             where status = 'pending'
             order by occurred_at
             limit :batchSize
             for update skip locked
            """, nativeQuery = true)
    List<DomainEvent> lockPendingBatch(@Param("batchSize") int batchSize);

    List<DomainEvent> findByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(String aggregateType, UUID aggregateId);

    List<DomainEvent> findByBrandIdOrderByOccurredAtDesc(UUID brandId, Pageable pageable);

    long countByStatus(String status);
}
