package com.influencer.content.api;

import com.influencer.content.domain.LandingPageCollaborator;
import com.influencer.content.infrastructure.LandingPageCollaboratorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Page collaborators (roadmap G.1). Tenancy and the confirmed-link check are enforced by the
 * BFF; this layer stores what it is told, like every other DAO controller here.
 */
@RestController
@RequestMapping("/landing-page-collaborators")
public class LandingPageCollaboratorController {
    private final LandingPageCollaboratorRepository repository;

    public LandingPageCollaboratorController(LandingPageCollaboratorRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<LandingPageCollaborator> findAll(@RequestParam(required = false) UUID landingTemplateId,
                                                 @RequestParam(required = false) UUID creatorIdentityId) {
        if (landingTemplateId != null && creatorIdentityId != null) {
            return repository
                    .findByLandingTemplateIdAndCreatorIdentityIdAndRevokedAtIsNull(landingTemplateId, creatorIdentityId)
                    .map(List::of).orElseGet(List::of);
        }
        if (landingTemplateId != null) {
            return repository.findByLandingTemplateIdAndRevokedAtIsNull(landingTemplateId);
        }
        if (creatorIdentityId != null) {
            return repository.findByCreatorIdentityIdAndRevokedAtIsNull(creatorIdentityId);
        }
        return List.of();
    }

    /**
     * Grant access.
     *
     * Returns the EXISTING active grant rather than inserting a duplicate, so re-inviting
     * someone who already has access is a quiet no-op instead of a constraint violation.
     */
    /**
     * Single-row read.
     *
     * Needed by the BFF's tenancy check on revoke. The list endpoint deliberately returns an
     * empty array when unfiltered — an unfiltered list would be a cross-tenant leak — so it
     * cannot serve as a lookup by id.
     */
    @GetMapping("/{id}")
    public LandingPageCollaborator findById(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("LandingPageCollaborator not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LandingPageCollaborator create(@RequestBody LandingPageCollaborator collaborator) {
        return repository.findByLandingTemplateIdAndCreatorIdentityIdAndRevokedAtIsNull(
                        collaborator.getLandingTemplateId(), collaborator.getCreatorIdentityId())
                .orElseGet(() -> repository.save(collaborator));
    }

    /**
     * Revoke in place rather than delete, so who had access and when survives the access.
     */
    @DeleteMapping("/{id}")
    public LandingPageCollaborator revoke(@PathVariable UUID id,
                                          @RequestParam(required = false) UUID revokedByUserId) {
        LandingPageCollaborator existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("LandingPageCollaborator not found"));
        existing.setRevokedAt(Instant.now());
        existing.setRevokedByUserId(revokedByUserId);
        return repository.save(existing);
    }
}
