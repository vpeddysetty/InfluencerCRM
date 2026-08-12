package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.ConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Reads and appends {@link ConsentRecord}s.
 *
 * <p>There is no update or delete method, and that is the point — see the class javadoc on
 * {@link ConsentRecord}. {@code JpaRepository} inherits {@code delete} and {@code save}; nothing
 * calls them for an existing row, and the entity's fields are all {@code updatable = false} so an
 * accidental merge cannot rewrite history.
 */
@Repository
public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    /** Everything one person ever agreed to — the subject-access-request query. */
    List<ConsentRecord> findBySubjectEmailIgnoreCaseOrderByCreatedAtDesc(String subjectEmail);

    /** One account's history for one document, newest first; the head row is the current state. */
    List<ConsentRecord> findBySubjectTypeAndSubjectIdAndConsentTypeOrderByCreatedAtDesc(
            String subjectType, UUID subjectId, String consentType);

    /** One account's full history across both documents. */
    List<ConsentRecord> findBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(
            String subjectType, UUID subjectId);
}
