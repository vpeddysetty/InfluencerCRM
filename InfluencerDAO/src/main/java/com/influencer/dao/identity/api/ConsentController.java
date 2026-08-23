package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.ConsentRecord;
import com.influencer.dao.identity.infrastructure.ConsentRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Storage for consent records — the evidence that someone accepted the terms or the privacy policy.
 *
 * <p>Append and read only. There is deliberately no update and no delete endpoint: a withdrawal is a
 * new row with {@code granted=false}, and an erasure request does not remove the proof that consent
 * was given. See {@link ConsentRecord} for why.
 */
@RestController
@RequestMapping("/consents")
public class ConsentController {

    private static final Set<String> SUBJECT_TYPES = Set.of(
            ConsentRecord.SUBJECT_USER,
            ConsentRecord.SUBJECT_CREATOR_IDENTITY,
            ConsentRecord.SUBJECT_LEAD);

    /** Matches the check constraint added in V39; see validate(). */
    private static final java.util.regex.Pattern SHA256_HEX =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    private static final Set<String> CONSENT_TYPES = Set.of(
            ConsentRecord.TYPE_TERMS,
            ConsentRecord.TYPE_PRIVACY);

    private final ConsentRecordRepository repository;

    public ConsentController(ConsentRecordRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentRecord record(@RequestBody ConsentRecord record) {
        validate(record);

        record.setId(null);
        record.setSubjectEmail(record.getSubjectEmail().trim().toLowerCase());

        try {
            return repository.save(record);
        } catch (DataIntegrityViolationException e) {
            // The partial unique index fired: this subject already has a live grant for this document
            // version. That is a RETRY, not an error — a double-clicked signup button or a client
            // retry after a timeout. Returning the existing row keeps signup idempotent, which
            // matters because the alternative is a failed signup for someone who did consent.
            return repository
                    .findBySubjectTypeAndSubjectIdAndConsentTypeOrderByCreatedAtDesc(
                            record.getSubjectType(), record.getSubjectId(), record.getConsentType())
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "Consent record conflicts with an existing row"));
        }
    }

    /** One account's consent history, newest first. */
    @GetMapping
    public List<ConsentRecord> bySubject(@RequestParam String subjectType,
                                         @RequestParam UUID subjectId) {
        requireSubjectType(subjectType);
        return repository.findBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(subjectType, subjectId);
    }

    /**
     * Everything one email address ever agreed to.
     *
     * <p>By email rather than id because a subject access request arrives as an email address, and
     * because a lead has no id to look up.
     */
    @GetMapping("/by-email")
    public List<ConsentRecord> byEmail(@RequestParam String email) {
        return repository.findBySubjectEmailIgnoreCaseOrderByCreatedAtDesc(email.trim().toLowerCase());
    }

    private void validate(ConsentRecord record) {
        requireSubjectType(record.getSubjectType());

        if (!CONSENT_TYPES.contains(record.getConsentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "consentType must be one of " + CONSENT_TYPES);
        }
        if (record.getSubjectEmail() == null || record.getSubjectEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectEmail is required");
        }
        if (record.getDocumentVersion() == null || record.getDocumentVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentVersion is required");
        }
        if (record.getSource() == null || record.getSource().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source is required");
        }
        // Mirrors the consent_records_subject_id_present constraint. Checked here too so the caller
        // gets a 400 naming the field rather than a 500 out of a constraint violation.
        if (!ConsentRecord.SUBJECT_LEAD.equals(record.getSubjectType()) && record.getSubjectId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "subjectId is required unless subjectType is " + ConsentRecord.SUBJECT_LEAD);
        }
        // Mirrors consent_records_document_sha256_format (V39), for the same reason as above.
        //
        // The value is optional -- absent means "captured before evidence capture existed", and a
        // caller that could not snapshot the document deliberately sends nothing rather than a
        // guess. But a value that IS present and malformed must not be stored: a hash that does not
        // describe the bytes is worse than no hash, because it reads as evidence right up until
        // somebody checks it.
        String sha256 = record.getDocumentSha256();
        if (sha256 != null && !sha256.isBlank() && !SHA256_HEX.matcher(sha256).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "documentSha256 must be 64 lowercase hex characters");
        }
    }

    private void requireSubjectType(String subjectType) {
        if (!SUBJECT_TYPES.contains(subjectType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "subjectType must be one of " + SUBJECT_TYPES);
        }
    }
}
