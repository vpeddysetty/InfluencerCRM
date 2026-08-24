package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.FederatedIdentity;
import com.influencer.dao.identity.infrastructure.FederatedIdentityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The mapping from an external provider assertion to a local user.
 *
 * <p>Two callers matter. Social sign-in links the identity on every successful authentication, so
 * the subject id is recorded the first time and refreshed thereafter. Meta's data-deletion callback
 * arrives knowing only a Facebook user id, and {@code GET /federated-identities/by-subject} is the
 * only way to turn that into a local user.
 */
@RestController
@RequestMapping("/federated-identities")
public class FederatedIdentityController {

    private final FederatedIdentityRepository repository;

    public FederatedIdentityController(FederatedIdentityRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/by-subject")
    public FederatedIdentity findBySubject(@RequestParam String provider, @RequestParam String subject) {
        return repository.findByProviderAndSubject(provider, subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Federated identity not found"));
    }

    @GetMapping
    public List<FederatedIdentity> findByUser(@RequestParam UUID userId) {
        return repository.findByUserId(userId);
    }

    /**
     * Records that a provider authenticated this user, creating the link if it is new.
     *
     * <p>An upsert rather than a plain insert: sign-in happens repeatedly and the second one must
     * not collide with the {@code (provider, subject)} unique constraint. Only
     * {@code last_authenticated_at} and the asserted email move on a repeat — {@code linked_at}
     * records when the link was first established and would be a lie if it tracked the latest login.
     *
     * <p>A subject already linked to a <em>different</em> user is rejected rather than reassigned.
     * Silently repointing it would migrate an external account between local users on the strength
     * of one request, which is an account takeover with a 200 response.
     */
    @PostMapping
    @Transactional
    public FederatedIdentity link(@RequestBody LinkRequest request) {
        if (request.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        // Provider names are ours, so normalising case keeps "Facebook" and "facebook" one
        // provider. Subject ids are the provider's and are opaque: lowercasing a case-sensitive
        // id would map two distinct external accounts onto one row.
        String provider = require(request.provider(), "provider").toLowerCase();
        String subject = require(request.subject(), "subject");

        Optional<FederatedIdentity> existing = repository.findByProviderAndSubject(provider, subject);
        if (existing.isPresent() && !existing.get().getUserId().equals(request.userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This " + provider + " identity is already linked to a different user");
        }

        FederatedIdentity identity = existing.orElseGet(() -> {
            FederatedIdentity created = new FederatedIdentity();
            created.setUserId(request.userId());
            created.setProvider(provider);
            created.setSubject(subject);
            created.setLinkedAt(Instant.now());
            return created;
        });

        identity.setAssertedEmail(request.assertedEmail());
        identity.setEmailVerifiedByIdp(request.emailVerifiedByIdp());
        identity.setLastAuthenticatedAt(Instant.now());
        return repository.save(identity);
    }

    /**
     * Removes a provider link.
     *
     * <p>Deliberately does not enforce the "keep one credential" rule: that policy needs to read the
     * user's password hash, and it lives in {@code CredentialPolicy} in the Identity context. A
     * deletion callback removing the only credential is the intended outcome there, not an error.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable UUID id) {
        repository.deleteById(id);
    }

    /**
     * Removes every link this user has to one provider.
     *
     * <p>The provider-scoped deletion {@code /data-deletion/} section 3.2 promises: erase what came
     * from Facebook without touching the account. The existing delete-by-id cannot express that,
     * because the caller knows the person and the provider but not the link's identifier.
     *
     * <p>Deleting nothing is success. The goal is that no link remains, and a user who never
     * connected that provider already satisfies it -- reporting an error would make a legitimate
     * request look like a failure.
     */
    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkProvider(@PathVariable UUID userId, @RequestParam String provider) {
        String normalized = require(provider, "provider").toLowerCase(java.util.Locale.ROOT);
        repository.findByUserId(userId).stream()
                .filter(identity -> normalized.equalsIgnoreCase(identity.getProvider()))
                .forEach(repository::delete);
    }

    private String require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value.trim();
    }

    public record LinkRequest(
            UUID userId,
            String provider,
            String subject,
            String assertedEmail,
            boolean emailVerifiedByIdp) {
    }
}
