package com.influencer.webe.shared.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up a creator identity by id, for contexts that must not import Identity.
 *
 * <p>Declared here in {@code shared} and implemented by the Identity context — the same inversion
 * {@link TokenVerifier} and {@link CreatorSessionVerifier} use, and for the same reason: ArchUnit
 * refuses a dependency from one context into another, and {@code content} needs a creator's email
 * address to tell them their page went live.
 *
 * <p><b>Deliberately narrower than the stored entity.</b> The DAO's creator endpoint returns the
 * whole row, {@code passwordHash} included. Publishing only id, email and display name keeps that
 * credential inside the one context that has any business handling it, and means a new column on
 * the entity does not silently become visible to every caller.
 */
public interface CreatorDirectory {

    /**
     * Empty when no such identity exists — an ordinary answer for a deleted account, not a fault.
     *
     * <p>Named {@code lookupCreator} rather than {@code findById} deliberately: the implementing
     * class already has a {@code findById} returning the raw projection, and Java cannot overload
     * on return type. Two methods one letter apart would be worse than one that says what it is.
     */
    Optional<Creator> lookupCreator(UUID creatorIdentityId);

    /**
     * @param email may be null for an identity created by an invitation that has not been redeemed
     */
    record Creator(UUID id, String email, String displayName) {
    }
}
