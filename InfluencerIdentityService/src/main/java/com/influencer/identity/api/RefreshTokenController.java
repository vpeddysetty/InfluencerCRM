package com.influencer.identity.api;

import com.influencer.identity.domain.RefreshToken;
import com.influencer.identity.infrastructure.RefreshTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for refresh tokens, on behalf of the BFF.
 *
 * <p>The BFF has no database of its own, so the store it used to keep in memory now lives here.
 * Endpoints deal exclusively in <em>hashes</em> — the raw token never leaves the BFF, so even this
 * internal API cannot leak a usable credential.
 */
@RestController
@RequestMapping("/refresh-tokens")
public class RefreshTokenController {

    private final RefreshTokenRepository repository;

    public RefreshTokenController(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RefreshToken issue(@RequestBody IssueRequest request) {
        RefreshToken token = new RefreshToken();
        token.setTokenHash(request.tokenHash());
        token.setUserId(request.userId());
        token.setProvider(request.provider());
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(request.expiresAt());
        return repository.save(token);
    }

    /**
     * Resolves a token by hash.
     *
     * <p>Returns 404 for expired or revoked tokens as well as unknown ones: to a caller deciding
     * whether to renew a session, all three mean the same thing, and distinguishing them would leak
     * whether a hash was ever valid.
     */
    @GetMapping("/{tokenHash}")
    public RefreshToken resolve(@PathVariable String tokenHash) {
        Optional<RefreshToken> found = repository.findByTokenHash(tokenHash);
        return found.filter(t -> t.isUsable(Instant.now()))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Refresh token not found or no longer usable"));
    }

    /** Consumes a token. Used both for rotation-on-use and for logout. */
    @DeleteMapping("/{tokenHash}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@PathVariable String tokenHash) {
        repository.deleteByTokenHash(tokenHash);
    }

    /** Revokes every session for a user. */
    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revokeAllForUser(@PathVariable UUID userId) {
        repository.deleteAllForUser(userId);
    }

    public record IssueRequest(String tokenHash, UUID userId, String provider, Instant expiresAt) {
    }
}
