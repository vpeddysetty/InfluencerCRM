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

    /**
     * Approve or refuse a pending claim.
     *
     * <p><b>The brand is checked here as well as in the BFF</b> (OP-18). The DAO generally trusts
     * the BFF for tenancy, and that convention is why this was missed: the link was loaded by
     * {@code linkId} alone, so the endpoint would confirm any link anyone named. Combined with a
     * BFF that checked only that the caller held {@code creator:write} — not that the link was
     * theirs — a user in any brand could confirm another brand's pending claim by guessing a UUID,
     * granting that creator access to the victim brand's negotiated terms.
     *
     * <p>The BFF fix alone would close it. This check is kept because the cost is one comparison
     * against a row already in hand, and because the failure it guards is silent: a confirmed link
     * looks identical however it was created, so nothing downstream would ever reveal that this
     * one was granted by the wrong brand.
     */
    @PostMapping("/links/{linkId}/decision")
    @Transactional
    public CreatorIdentityLink decide(@PathVariable UUID linkId, @RequestBody LinkDecision decision) {
        CreatorIdentityLink link = linkRepository.findById(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
        if (!"confirmed".equals(decision.status()) && !"rejected".equals(decision.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be confirmed or rejected");
        }
        // 404, not 403: a caller probing link ids must not learn which ones exist, which is the
        // same reasoning the page-collaboration paths use for their ownership checks.
        if (decision.brandId() == null || !decision.brandId().equals(link.getBrandId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found");
        }
        link.setStatus(decision.status());
        link.setConfirmedByUserId(decision.decidedByUserId());
        link.setConfirmedAt(Instant.now());
        link.setUpdatedAt(Instant.now());
        return linkRepository.save(link);
    }

    /**
     * @param brandId the deciding brand. Required — a null is refused rather than waved through,
     *                so an old caller that has not been updated fails loudly instead of silently
     *                keeping the unscoped behaviour this field exists to remove.
     */
    public record LinkDecision(String status, UUID decidedByUserId, UUID brandId) {
    }
}
