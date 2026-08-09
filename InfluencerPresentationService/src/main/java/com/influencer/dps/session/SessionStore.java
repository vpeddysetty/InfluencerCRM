package com.influencer.dps.session;

import java.util.Optional;
import java.util.UUID;

/**
 * Where browser sessions live.
 *
 * <p>An interface rather than a class because the storage choice is a deployment decision, not an
 * application one. One DPS instance is fine with the in-memory implementation; the moment there are
 * two behind a load balancer, instance B must see a session created by instance A or users get
 * logged out at random. Swapping to Redis then means providing a different bean, and nothing that
 * <em>uses</em> a session changes.
 *
 * <p>The same seam serves the login-time cache requirement: warm data is stored on the session, so
 * whatever backs this interface also backs the cache.
 */
public interface SessionStore {

    void save(UiSession session);

    /**
     * Loads a session by id, treating an expired one as absent.
     *
     * <p>Implementations should apply sliding expiry here — reading a session is evidence the user
     * is active, and an active user should not be logged out mid-task.
     */
    Optional<UiSession> find(String sessionId);

    void delete(String sessionId);

    /**
     * Ends every session for a user.
     *
     * <p>Needed for "log me out everywhere" and for revoking access when a membership is removed —
     * without it, a removed user keeps working until their session happens to lapse.
     */
    int deleteAllForUser(UUID userId);

    /** Live session count. Exposed for operational visibility, not for application logic. */
    long size();
}
