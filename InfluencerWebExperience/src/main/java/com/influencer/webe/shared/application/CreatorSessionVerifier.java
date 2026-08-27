package com.influencer.webe.shared.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a creator-portal token into the creator it belongs to.
 *
 * <p>Declared here, in {@code shared}, and <em>implemented</em> by the Identity context — the same
 * inversion {@link TokenVerifier} uses, and for the same reason. {@code
 * CreatorTokenAuthenticationFilter} lives in {@code security}, which is cross-cutting and must not
 * depend on any single context: importing Identity's {@code CreatorPortalService} directly would
 * mean extracting Identity broke the security filter chain, and ArchUnit refuses it outright.
 *
 * <p><b>Deliberately narrower than the session record it wraps.</b> The filter needs to know which
 * creator is calling and nothing else — not their email, not their display name, and certainly not
 * their token. Publishing only the identity keeps the credential inside the context that mints it,
 * and means a future change to the session's shape does not reach across the boundary.
 */
public interface CreatorSessionVerifier {

    /**
     * Returns the creator identity behind a live portal session, or empty when the token is
     * absent, unknown, expired or revoked.
     *
     * <p>Implementations must re-read the session on every call rather than trusting a cache: that
     * is what makes revoking a creator's access immediate instead of effective at token expiry.
     * Callers must treat empty as unauthenticated — never as a reason to fall back to any
     * caller-supplied identity.
     */
    Optional<UUID> verifyCreatorToken(String token);
}
