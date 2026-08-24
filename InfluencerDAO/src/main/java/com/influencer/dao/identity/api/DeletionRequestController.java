package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.DeletionRequest;
import com.influencer.dao.identity.infrastructure.DeletionRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * Storage for deletion requests.
 *
 * <p>Append and update only. There is deliberately no delete endpoint: the record that a deletion
 * was requested and carried out is the evidence it was lawful, and an erasure request does not
 * erase the proof that it was honoured. Same rule as {@link ConsentController}.
 *
 * <p><b>This is the live one.</b> A class of the same name exists in
 * {@code InfluencerIdentityService}, which serves no traffic — see CLAUDE.md §1. Changing that one
 * changes nothing a user sees.
 */
@RestController
@RequestMapping("/deletion-requests")
public class DeletionRequestController {

    private static final Set<String> SCOPES =
            Set.of(DeletionRequest.SCOPE_ACCOUNT, DeletionRequest.SCOPE_PROVIDER);

    private static final Set<String> SOURCES =
            Set.of(DeletionRequest.SOURCE_EMAIL, DeletionRequest.SOURCE_MANUAL);

    private final DeletionRequestRepository repository;

    public DeletionRequestController(DeletionRequestRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeletionRequest record(@RequestBody DeletionRequest request) {
        validate(request);
        request.setId(null);
        request.setSubjectEmail(request.getSubjectEmail().trim().toLowerCase());

        // SNS delivers at least once. Returning the existing row rather than creating a second one
        // keeps a redelivery harmless: two rows would mean two notifications and two approval links,
        // each authorising an irreversible act on the same person.
        if (request.getRawMessageS3Key() != null && !request.getRawMessageS3Key().isBlank()
                && repository.existsByRawMessageS3Key(request.getRawMessageS3Key())) {
            return repository.findBySubjectEmailIgnoreCaseOrderByRequestedAtDesc(request.getSubjectEmail())
                    .stream()
                    .filter(existing -> request.getRawMessageS3Key().equals(existing.getRawMessageS3Key()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "A request for this message exists but could not be read back"));
        }

        return repository.save(request);
    }

    /** One request by id. */
    @GetMapping("/{id}")
    public DeletionRequest byId(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such deletion request"));
    }

    /**
     * Look up a request by the hash of its approval token.
     *
     * <p>By hash, never by token: the caller hashes what the operator clicked and asks whether it
     * matches. The token itself is never transmitted here and never stored.
     */
    @GetMapping("/by-approval-token")
    public DeletionRequest byApprovalToken(@RequestParam String tokenHash) {
        return repository.findByApprovalTokenHash(tokenHash).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such approval token"));
    }

    /** Everything ever requested for one address — answers a subject access request. */
    @GetMapping("/by-email")
    public List<DeletionRequest> byEmail(@RequestParam String email) {
        return repository.findBySubjectEmailIgnoreCaseOrderByRequestedAtDesc(
                email.trim().toLowerCase());
    }

    /** The operator queue: arrived, not yet settled, oldest first. */
    @GetMapping("/open")
    public List<DeletionRequest> open() {
        return repository.findOpen();
    }

    /**
     * Records progress on an existing request.
     *
     * <p>Only the workflow fields move. The request itself — who asked, when, for what — is never
     * rewritten, because that is the part being evidenced.
     */
    @PatchMapping("/{id}")
    public DeletionRequest update(@PathVariable UUID id, @RequestBody DeletionRequest patch) {
        DeletionRequest existing = repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such deletion request"));

        if (patch.getAcknowledgedAt() != null) {
            existing.setAcknowledgedAt(patch.getAcknowledgedAt());
        }
        if (patch.getApprovedAt() != null) {
            // Mirrors the V40 check constraint, so the caller gets a 400 naming the field rather
            // than a 500 out of a constraint violation.
            if (patch.getApprovedBy() == null || patch.getApprovedBy().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "approvedBy is required when approvedAt is set");
            }
            existing.setApprovedAt(patch.getApprovedAt());
            existing.setApprovedBy(patch.getApprovedBy());
        }
        if (patch.getCompletedAt() != null) {
            if (existing.getApprovedAt() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A request cannot be completed before it is approved");
            }
            existing.setCompletedAt(patch.getCompletedAt());
        }
        if (patch.getRefusedAt() != null) {
            existing.setRefusedAt(patch.getRefusedAt());
            existing.setRefusedReason(patch.getRefusedReason());
        }
        if (patch.getOutcomeNote() != null) {
            existing.setOutcomeNote(patch.getOutcomeNote());
        }
        if (patch.getOperatorNotifiedAt() != null) {
            existing.setOperatorNotifiedAt(patch.getOperatorNotifiedAt());
        }
        if (patch.getRequesterNotifiedAt() != null) {
            existing.setRequesterNotifiedAt(patch.getRequesterNotifiedAt());
        }
        if (patch.getApprovalTokenHash() != null) {
            existing.setApprovalTokenHash(patch.getApprovalTokenHash());
            existing.setApprovalExpiresAt(patch.getApprovalExpiresAt());
        }
        // subject_user_id is cleared by the purge once the user row is gone.
        if (patch.getSubjectUserId() == null && existing.getCompletedAt() == null) {
            // No-op: an absent id in a patch means "unchanged", not "clear it".
            return repository.save(existing);
        }
        return repository.save(existing);
    }

    private void validate(DeletionRequest request) {
        if (request.getSubjectEmail() == null || request.getSubjectEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectEmail is required");
        }
        if (request.getScope() != null && !SCOPES.contains(request.getScope())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope must be one of " + SCOPES);
        }
        if (DeletionRequest.SCOPE_PROVIDER.equals(request.getScope())
                && (request.getProvider() == null || request.getProvider().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "provider is required when scope is 'provider'");
        }
        if (request.getIntakeSource() != null && !SOURCES.contains(request.getIntakeSource())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "intakeSource must be one of " + SOURCES);
        }
    }
}
