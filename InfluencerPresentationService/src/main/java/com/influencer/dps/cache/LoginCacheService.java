package com.influencer.dps.cache;

import com.influencer.dps.session.UiSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every registered {@link LoginCacheWarmer} once, at login.
 *
 * <p>Warming is best-effort by design. A warmer that fails logs and contributes nothing; the login
 * still succeeds and the remote fetches normally. The alternative — a slow or broken reference-data
 * call preventing sign-in altogether — trades a minor performance win for a total outage.
 */
@Service
public class LoginCacheService {

    private static final Logger log = LoggerFactory.getLogger(LoginCacheService.class);

    private final List<LoginCacheWarmer> warmers;

    public LoginCacheService(List<LoginCacheWarmer> warmers) {
        this.warmers = warmers;
        if (warmers.isEmpty()) {
            log.info("No login cache warmers registered. Sessions carry no warm data; remotes fetch "
                    + "on mount as usual. Register a LoginCacheWarmer bean to change that.");
        } else {
            log.info("Login cache warmers active: {}", warmers.stream().map(LoginCacheWarmer::key).toList());
        }
    }

    /** Returns the session with its warm cache populated. */
    public UiSession warm(UiSession session) {
        if (warmers.isEmpty()) {
            return session;
        }

        Map<String, Object> cache = new LinkedHashMap<>();
        long startedAt = System.currentTimeMillis();

        for (LoginCacheWarmer warmer : warmers) {
            if (!warmer.appliesTo(session)) {
                continue;
            }
            try {
                Map<String, Object> value = warmer.warm(session);
                if (value != null && !value.isEmpty()) {
                    cache.put(warmer.key(), value);
                }
            } catch (Exception exception) {
                // Never fail a login over a cache. Degraded is acceptable; unable to sign in is not.
                log.warn("Login cache warmer '{}' failed for user {}; continuing without it",
                        warmer.key(), session.userId(), exception);
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > 1000) {
            // Warming happens on the login path, so slowness here is felt directly by the user.
            log.warn("Login cache warming took {}ms for user {}. That cost is paid on the login "
                    + "path — consider deferring the slower slices.", elapsed, session.userId());
        }

        return session.withWarmCache(Map.copyOf(cache));
    }
}
