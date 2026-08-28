package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.CreatorPortalSession;
import com.influencer.dao.identity.infrastructure.CreatorPortalSessionRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Storage for creator portal sessions (roadmap PR-40).
 *
 * <p><b>This endpoint only ever sees hashes.</b> The BFF hashes the token before it gets here and
 * the raw value is never sent, stored or logged — so this controller cannot leak a working
 * credential even if its responses were captured. That is why the path segment is
 * {@code {tokenHash}} rather than {@code {token}}: the name is the reminder.
 *
 * <p>Expiry is enforced on READ rather than by a sweep, so a lapsed session stops working at the
 * moment it lapses instead of at the next housekeeping run. The sweep exists only to stop the
 * table growing.
 */
@RestController
@RequestMapping("/creator-portal-sessions")
public class CreatorPortalSessionController {

    /**
     * How long an expired row is kept before housekeeping removes it.
     *
     * <p>Not zero: a session that expired seconds ago is exactly the one someone is asking about
     * when they report being signed out, and deleting it immediately would remove the evidence.
     */
    private static final java.time.Duration EXPIRED_RETENTION = java.time.Duration.ofDays(7);

    private final CreatorPortalSessionRepository repository;

    public CreatorPortalSessionController(CreatorPortalSessionRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreatorPortalSession create(@RequestBody CreatorPortalSession session) {
        if (session.getTokenHash() == null || session.getTokenHash().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tokenHash is required");
        }
        if (session.getCreatorIdentityId() == null || session.getExpiresAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "creatorIdentityId and expiresAt are required");
        }
        if (session.getCreatedAt() == null) {
            session.setCreatedAt(Instant.now());
        }
        return repository.save(session);
    }

    /**
     * Resolve a live session, or 404.
     *
     * <p>404 for expired and revoked alike, and for unknown: the caller needs one answer — "this
     * token is not usable" — and distinguishing the cases would tell someone probing hashes which
     * ones were real.
     */
    @GetMapping("/{tokenHash}")
    public CreatorPortalSession find(@PathVariable String tokenHash) {
        CreatorPortalSession session = repository.findById(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No session"));
        if (session.getRevokedAt() != null || session.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No session");
        }
        return session;
    }

    /** Sign out. 204 whether or not a live session was found — signing out is idempotent. */
    @DeleteMapping("/{tokenHash}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@PathVariable String tokenHash) {
        repository.revoke(tokenHash, Instant.now());
    }

    /** Revoke every live session for one creator — password change, or suspected compromise. */
    @DeleteMapping("/by-creator/{creatorIdentityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revokeAll(@PathVariable UUID creatorIdentityId) {
        repository.revokeAllForCreator(creatorIdentityId, Instant.now());
    }

    /**
     * Housekeeping. Called by a sweep, not on the request path.
     *
     * <p>Deliberately an explicit endpoint rather than a {@code @Scheduled} job in the DAO: the
     * relay and the schedulers all live in the BFF, and a second scheduling home would be a second
     * place to look when something does not run.
     */
    @DeleteMapping("/expired")
    public int purgeExpired() {
        return repository.deleteExpiredBefore(Instant.now().minus(EXPIRED_RETENTION));
    }
}
