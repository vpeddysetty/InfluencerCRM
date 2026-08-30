package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.CreatorIdentity;
import com.influencer.dao.identity.domain.CreatorIdentityLink;
import com.influencer.dao.identity.domain.CreatorInvite;
import com.influencer.dao.identity.infrastructure.CreatorIdentityLinkRepository;
import com.influencer.dao.identity.infrastructure.CreatorIdentityRepository;
import com.influencer.dao.identity.infrastructure.CreatorInviteRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creator invitations, and the redemption that breaks the bootstrap circularity (roadmap PR-41).
 *
 * <p><b>Only hashes cross this boundary.</b> The BFF hashes the token before calling, so the path
 * segment is {@code {tokenHash}} rather than {@code {token}} — the name is the reminder that this
 * controller cannot leak a working invitation even if its responses were captured.
 */
@RestController
@RequestMapping("/creator-invites")
public class CreatorInviteController {

    private final CreatorInviteRepository inviteRepository;
    private final CreatorIdentityRepository identityRepository;
    private final CreatorIdentityLinkRepository linkRepository;

    public CreatorInviteController(CreatorInviteRepository inviteRepository,
                                   CreatorIdentityRepository identityRepository,
                                   CreatorIdentityLinkRepository linkRepository) {
        this.inviteRepository = inviteRepository;
        this.identityRepository = identityRepository;
        this.linkRepository = linkRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreatorInvite create(@RequestBody CreatorInvite invite) {
        if (invite.getBrandId() == null || invite.getEmail() == null
                || invite.getTokenHash() == null || invite.getExpiresAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "brandId, email, tokenHash and expiresAt are required");
        }
        invite.setStatus("pending");
        return inviteRepository.save(invite);
    }

    @GetMapping
    public List<CreatorInvite> list(@RequestParam UUID brandId,
                                    @RequestParam(required = false) String status) {
        return status == null || status.isBlank()
                ? inviteRepository.findByBrandIdOrderByCreatedAtDesc(brandId)
                : inviteRepository.findByBrandIdAndStatusOrderByCreatedAtDesc(brandId, status);
    }

    /**
     * Look up an invitation without redeeming it — the invite screen's "who is this from?".
     *
     * <p>Expired invitations are returned rather than hidden, with their status, so the screen can
     * say "this link has expired, ask Acme for a new one" instead of a 404. A dead end that looks
     * like a broken link sends people to support; one that explains itself does not.
     */
    @GetMapping("/by-token/{tokenHash}")
    public CreatorInvite findByToken(@PathVariable String tokenHash) {
        CreatorInvite invite = inviteRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No invitation"));
        // Reported as expired on read rather than by a sweep, so the answer is right the moment it
        // becomes right instead of at the next housekeeping run.
        if ("pending".equals(invite.getStatus()) && invite.getExpiresAt().isBefore(Instant.now())) {
            invite.setStatus("expired");
        }
        return invite;
    }

    /**
     * Redeem an invitation: creates or finds the identity, confirms the link, marks it accepted.
     *
     * <p><b>All three in one transaction, and that is the point of doing it here rather than in the
     * BFF.</b> A partial redemption is worse than a failed one in both directions: an identity with
     * no link is an account that can sign in and see nothing, and a confirmed link against an
     * invitation still marked pending is a token that can be redeemed twice.
     *
     * <p>The link is created {@code confirmed}, not {@code claimed}. That is safe precisely because
     * the brand issued the invitation — the approval the claim flow waits for has already happened,
     * and asking them to approve their own invitation would be theatre. The token is what carries
     * the brand's decision.
     */
    @PostMapping("/by-token/{tokenHash}/redeem")
    @Transactional
    public CreatorIdentityLink redeem(@PathVariable String tokenHash,
                                      @RequestBody RedeemRequest request) {
        CreatorInvite invite = inviteRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No invitation"));

        if (!"pending".equals(invite.getStatus())) {
            // 409 rather than 404: the caller holds a real token and needs to know it was already
            // used or withdrawn, which is a different problem from a mistyped link.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This invitation has already been " + invite.getStatus() + ".");
        }
        if (invite.getExpiresAt().isBefore(Instant.now())) {
            invite.setStatus("expired");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This invitation has expired. Ask the brand for a new one.");
        }

        // The identity is matched by the INVITED address, never by one the caller supplies:
        // otherwise a forwarded invitation would let whoever holds it attach it to their own
        // account. Same reasoning as MemberInvitationService's email check.
        Optional<CreatorIdentity> existing = identityRepository.findByEmailIgnoreCase(invite.getEmail());
        CreatorIdentity identity = existing.orElseGet(() -> {
            CreatorIdentity created = new CreatorIdentity();
            created.setEmail(invite.getEmail());
            created.setDisplayName(request.displayName());
            // Null until they set one. A creator redeeming an invitation has not chosen a password
            // yet, and inventing a placeholder here would be a credential nobody meant to create.
            created.setPasswordHash(request.passwordHash());
            // Set here, not left to the column default. `created_at` and `updated_at` are
            // `not null default now()`, which reads as though the database fills them in -- but a
            // Postgres default applies only when the column is OMITTED from the INSERT, and both
            // are mapped fields Hibernate always names, so an unset field is written as an explicit
            // NULL and rejected. Redeeming an invitation was the ONE identity path that did not do
            // this; every other one (CreatorIdentityController, the portal session, email
            // verification, tenancy) already called setCreatedAt, which is why nothing else broke.
            Instant createdNow = Instant.now();
            created.setCreatedAt(createdNow);
            created.setUpdatedAt(createdNow);
            return identityRepository.save(created);
        });

        // If a link already exists, confirm it rather than adding a second: a creator invited twice
        // must not end up with two rows, which would make revocation remove only one of them.
        CreatorIdentityLink link = linkRepository
                .findByCreatorIdentityIdAndBrandId(identity.getId(), invite.getBrandId())
                .orElseGet(() -> {
                    CreatorIdentityLink fresh = new CreatorIdentityLink();
                    fresh.setCreatorIdentityId(identity.getId());
                    fresh.setBrandId(invite.getBrandId());
                    fresh.setCreatorId(invite.getCreatorId());
                    // Same reason as the identity above: creator_identity_links.created_at is
                    // not-null with a default the ORM never lets the database apply. updatedAt is
                    // stamped below for both the new and the existing row.
                    fresh.setCreatedAt(Instant.now());
                    return fresh;
                });
        link.setStatus("confirmed");
        link.setConfirmedByUserId(invite.getInvitedByUserId());
        link.setConfirmedAt(Instant.now());
        link.setUpdatedAt(Instant.now());
        CreatorIdentityLink saved = linkRepository.save(link);

        invite.setStatus("accepted");
        invite.setAcceptedAt(Instant.now());
        invite.setAcceptedByCreatorIdentityId(identity.getId());
        inviteRepository.save(invite);

        return saved;
    }

    /** Withdraw an unredeemed invitation. */
    @PostMapping("/{id}/revoke")
    @Transactional
    public CreatorInvite revoke(@PathVariable UUID id) {
        CreatorInvite invite = inviteRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No invitation"));
        if ("accepted".equals(invite.getStatus())) {
            // Revoking an accepted invitation would imply it undoes the access it granted, and it
            // does not -- that is what revoking the COLLABORATOR GRANT is for. Refusing here keeps
            // the two operations from being confused at the moment somebody is trying to cut
            // access off in a hurry.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This invitation was already accepted. Revoke the creator's access instead.");
        }
        invite.setStatus("revoked");
        return inviteRepository.save(invite);
    }

    /**
     * @param displayName  what to call them, from the invite screen. Optional.
     * @param passwordHash hashed by the BFF, never a raw password. Null when the identity already
     *                     exists, which is the case for a creator invited by a second brand.
     */
    public record RedeemRequest(String displayName, String passwordHash) {
    }
}
