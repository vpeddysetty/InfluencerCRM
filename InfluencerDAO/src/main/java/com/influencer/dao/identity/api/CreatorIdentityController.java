package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.CreatorIdentity;
import com.influencer.dao.identity.domain.CreatorIdentityLink;
import com.influencer.dao.identity.infrastructure.CreatorIdentityLinkRepository;
import com.influencer.dao.identity.infrastructure.CreatorIdentityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Storage for creator logins and the per-brand rows they speak for (roadmap Stage 4).
 *
 * <p>Kept apart from {@link TenancyController} because a creator is not a tenant. Every endpoint
 * there answers "what may this account reach"; these answer the inverse — "which brands hold a row
 * for this creator" — and mixing them would invite a query that treats a creator like a member.
 */
@RestController
@RequestMapping("/creator-identities")
public class CreatorIdentityController {

    private final CreatorIdentityRepository identityRepository;
    private final CreatorIdentityLinkRepository linkRepository;

    public CreatorIdentityController(CreatorIdentityRepository identityRepository,
                                     CreatorIdentityLinkRepository linkRepository) {
        this.identityRepository = identityRepository;
        this.linkRepository = linkRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorIdentity create(@RequestBody CreatorIdentity identity) {
        if (identity.getEmail() == null || identity.getEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
        }
        String email = identity.getEmail().trim().toLowerCase();
        identityRepository.findByEmailIgnoreCase(email).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Creator identity already exists");
        });
        identity.setId(null);
        identity.setEmail(email);
        identity.setCreatedAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        return identityRepository.save(identity);
    }

    @GetMapping("/by-email")
    public CreatorIdentity byEmail(@RequestParam String email) {
        return identityRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator identity not found"));
    }

    @GetMapping("/{id}")
    public CreatorIdentity findById(@PathVariable UUID id) {
        return identityRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator identity not found"));
    }

    // ------------------------------------------------------------------ links

    /**
     * Records a link between a login and a brand's creator row.
     *
     * <p>{@code status} decides whether this is a creator's unverified claim or a brand's
     * confirmation. Only the latter grants visibility, which is why a brand-initiated link is
     * created directly as {@code confirmed} and a creator-initiated one is not.
     */
    @PostMapping("/{identityId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreatorIdentityLink createLink(@PathVariable UUID identityId,
                                          @RequestBody CreatorIdentityLink link) {
        identityRepository.findById(identityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator identity not found"));
        if (link.getCreatorId() == null || link.getBrandId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creatorId and brandId are required");
        }

        // A creator row already confirmed to someone else must not be claimable: two identities
        // holding the same row would each see the other's brand relationship.
        linkRepository.findByCreatorIdAndStatus(link.getCreatorId(), "confirmed")
                .filter(existing -> !existing.getCreatorIdentityId().equals(identityId))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "That creator record is already linked to another identity");
                });

        CreatorIdentityLink saved = linkRepository
                .findByCreatorIdentityIdAndCreatorId(identityId, link.getCreatorId())
                .orElseGet(CreatorIdentityLink::new);
        saved.setCreatorIdentityId(identityId);
        saved.setCreatorId(link.getCreatorId());
        saved.setBrandId(link.getBrandId());
        saved.setStatus(link.getStatus() == null || link.getStatus().isBlank() ? "claimed" : link.getStatus());
        saved.setConfirmedByUserId(link.getConfirmedByUserId());
        if ("confirmed".equals(saved.getStatus()) && saved.getConfirmedAt() == null) {
            saved.setConfirmedAt(Instant.now());
        }
        if (saved.getCreatedAt() == null) {
            saved.setCreatedAt(Instant.now());
        }
        saved.setUpdatedAt(Instant.now());
        return linkRepository.save(saved);
    }

    @GetMapping("/{identityId}/links")
    public List<CreatorIdentityLink> links(@PathVariable UUID identityId,
                                           @RequestParam(required = false) String status) {
        return status == null || status.isBlank()
                ? linkRepository.findByCreatorIdentityId(identityId)
                : linkRepository.findByCreatorIdentityIdAndStatus(identityId, status);
    }

    /** Pending claims a brand needs to approve or refuse. */
    @GetMapping("/links/pending")
    public List<CreatorIdentityLink> pendingForBrand(@RequestParam UUID brandId) {
        return linkRepository.findByBrandIdAndStatus(brandId, "claimed");
    }

    @PostMapping("/links/{linkId}/decision")
    @Transactional
    public CreatorIdentityLink decide(@PathVariable UUID linkId, @RequestBody LinkDecision decision) {
        CreatorIdentityLink link = linkRepository.findById(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
        if (!"confirmed".equals(decision.status()) && !"rejected".equals(decision.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be confirmed or rejected");
        }
        link.setStatus(decision.status());
        link.setConfirmedByUserId(decision.decidedByUserId());
        link.setConfirmedAt(Instant.now());
        link.setUpdatedAt(Instant.now());
        return linkRepository.save(link);
    }

    public record LinkDecision(String status, UUID decidedByUserId) {
    }
}
