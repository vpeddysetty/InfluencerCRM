package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.DeletionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deletion requests.
 *
 * <p>No delete method, deliberately — V37 withholds the grant for the same reason. Purging the audit
 * trail of a purge is not an operation this service should be able to perform.
 */
@Repository
public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {

    /** Redeeming an approval link. Unique by the partial index added in V40. */
    Optional<DeletionRequest> findByApprovalTokenHash(String approvalTokenHash);

    /** Everything ever requested for one address — answers "did you honour that?". */
    List<DeletionRequest> findBySubjectEmailIgnoreCaseOrderByRequestedAtDesc(String subjectEmail);

    /**
     * The operator queue: what has arrived and not yet been settled.
     *
     * <p>Oldest first, because the published clock started when it arrived and the oldest is the
     * closest to breaching it.
     */
    @Query("""
            select d from DeletionRequest d
            where d.completedAt is null and d.refusedAt is null
            order by d.requestedAt asc
            """)
    List<DeletionRequest> findOpen();

    /**
     * Whether this exact message has already been recorded.
     *
     * <p>SNS delivers at least once, so the same inbound message can arrive twice. Without this a
     * retry becomes a second request for the same person, a second notification, and a second
     * approval link authorising a second irreversible act.
     */
    boolean existsByRawMessageS3Key(String rawMessageS3Key);
}
