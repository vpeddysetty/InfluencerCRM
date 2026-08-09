package com.influencer.webe.shared.application;

import com.influencer.webe.security.TenantContext;

import java.util.Optional;

/**
 * Verifies an access token and returns the caller's tenant context.
 *
 * <p>Declared here, in {@code shared}, and <em>implemented</em> by the Identity context. Every
 * context needs to authenticate a request, so {@code RequestUserResolver} lives in shared — but it
 * previously imported Identity's {@code SessionService} directly, which meant extracting Identity
 * would have broken all seven contexts at once.
 *
 * <p>Inverting the dependency this way is what makes Identity extractable: shared owns the contract,
 * Identity satisfies it, and when Identity becomes its own service only the implementation changes.
 */
public interface TokenVerifier {

    /**
     * Returns the verified caller, or empty when the token is absent, malformed, expired or
     * signed by an unknown key. Callers must treat empty as unauthenticated — never as a reason
     * to fall back to caller-supplied identity.
     */
    Optional<TenantContext> verify(String accessToken);
}
