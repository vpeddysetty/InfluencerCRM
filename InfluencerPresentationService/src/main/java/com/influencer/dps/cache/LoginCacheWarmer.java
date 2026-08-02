package com.influencer.dps.cache;

import com.influencer.dps.session.UiSession;

import java.util.Map;

/**
 * Assembles data into the session at login, so the first screen does not wait on N round trips.
 *
 * <p>Without this, every remote fetches its own reference data on mount: six origins, six requests,
 * six spinners — for data that is identical and changes rarely. Warming it once at login turns that
 * into a single server-side fan-out the user never waits on twice.
 *
 * <p><strong>The DPS is the only place this can live.</strong> A cache in the React layer dies on
 * refresh and cannot be shared across origins. A cache in one context service can only warm its own
 * data. The DPS already sits at the login boundary, already holds the session, and already knows the
 * brand — so it is the one component with both the trigger and the scope.
 *
 * <h3>Implementing one</h3>
 * Register a bean; {@code LoginCacheService} discovers and runs all of them. Guidance:
 * <ul>
 *   <li><strong>Warm only what the first screen needs.</strong> A cache that loads everything moves
 *       the latency from first-paint into login, which the user notices more.</li>
 *   <li><strong>Never throw.</strong> A warmer that fails must not fail the login — return an empty
 *       map and let the remote fetch normally. Degraded is fine; unable to sign in is not.</li>
 *   <li><strong>Scope to the session's brand.</strong> The cache is dropped on brand switch for
 *       exactly this reason; a warmer that ignores {@code brandId} would reintroduce cross-tenant
 *       leakage.</li>
 * </ul>
 */
public interface LoginCacheWarmer {

    /**
     * A stable key for this warmer's slice of the cache, e.g. {@code "brands"} or
     * {@code "campaignSummary"}. Remotes read it by this name.
     */
    String key();

    /**
     * Builds the cached value for a freshly authenticated session.
     *
     * @return the value to cache, or an empty map to contribute nothing. Must not throw.
     */
    Map<String, Object> warm(UiSession session);

    /**
     * Whether to run at all for this session — e.g. skip finance data for a role that cannot see it.
     * Warming data the user is not permitted to read wastes work and risks leaking it into a
     * response.
     */
    default boolean appliesTo(UiSession session) {
        return true;
    }
}
