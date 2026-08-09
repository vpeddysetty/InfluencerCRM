package com.influencer.identity.application;

import com.influencer.identity.domain.FederatedIdentity;
import com.influencer.identity.domain.User;
import com.influencer.identity.infrastructure.FederatedIdentityRepository;
import com.influencer.identity.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Enforces the one rule federation adds to the Identity context: a user must always retain at least
 * one way to authenticate.
 *
 * <p>Before federation this was structural — {@code password_hash} was NOT NULL, so a user without a
 * credential could not be represented. Making it nullable removes that guarantee, and unlinking the
 * last provider from a password-less account would otherwise produce a row nobody can ever log in
 * as: not an error at the time, just an account that silently stops working.
 *
 * <p>The rule spans two tables, so it cannot be a check constraint and does not fit inside a single
 * entity. It lives here, in one place, rather than being restated at each call site.
 */
@Service
public class CredentialPolicy {

    private final UserRepository users;
    private final FederatedIdentityRepository federatedIdentities;

    public CredentialPolicy(UserRepository users, FederatedIdentityRepository federatedIdentities) {
        this.users = users;
        this.federatedIdentities = federatedIdentities;
    }

    /**
     * Removes a provider link, refusing when it is the user's last credential.
     *
     * @throws IllegalStateException if unlinking would leave the user unable to authenticate
     */
    @Transactional
    public void unlink(UUID userId, String provider) {
        List<FederatedIdentity> linked = federatedIdentities.findByUserId(userId);
        FederatedIdentity target = linked.stream()
                .filter(identity -> identity.getProvider().equals(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No " + provider + " identity is linked to this user"));

        if (linked.size() == 1 && !hasPassword(userId)) {
            throw new IllegalStateException(
                    "Cannot unlink " + provider + ": it is this user's only credential. "
                            + "Set a password or link another provider first.");
        }

        federatedIdentities.delete(target);
    }

    /** Whether the user could still sign in if every federated identity were removed. */
    public boolean hasPassword(UUID userId) {
        return users.findById(userId)
                .map(User::getPasswordHash)
                .filter(hash -> !hash.isBlank())
                .isPresent();
    }

    /**
     * How many distinct ways this user can authenticate.
     *
     * <p>Surfaced on account settings so the last credential can be shown as non-removable, rather
     * than the user discovering the rule by hitting an error.
     */
    public long credentialCount(UUID userId) {
        return federatedIdentities.countByUserId(userId) + (hasPassword(userId) ? 1 : 0);
    }
}
