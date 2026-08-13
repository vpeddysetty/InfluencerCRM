package com.influencer.identity.infrastructure;

import com.influencer.identity.domain.DeletionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {

    /**
     * Open requests, oldest first.
     *
     * <p>This is the operator's queue, and the reason the 30-day completion promise on
     * /data-deletion/ is auditable rather than aspirational: anything near the top of this list
     * that is still open is overdue.
     */
    @Query("select d from DeletionRequest d "
            + "where d.completedAt is null and d.refusedAt is null "
            + "order by d.requestedAt asc")
    List<DeletionRequest> findOpen();

    /** Every request for an address, including ones whose user row is already gone. */
    List<DeletionRequest> findBySubjectEmailIgnoreCaseOrderByRequestedAtDesc(String subjectEmail);

    List<DeletionRequest> findBySubjectUserId(UUID subjectUserId);
}
