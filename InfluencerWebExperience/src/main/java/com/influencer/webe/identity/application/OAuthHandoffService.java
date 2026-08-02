package com.influencer.webe.identity.application;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a completed OAuth sign-in for the few seconds between the provider callback and the DPS
 * collecting it.
 *
 * <p>The browser is redirected with only an opaque handoff code. The tokens stay here and are
 * fetched server-to-server by the DPS, which means they never enter a URL, a fragment, a browser
 * history entry, a referrer header, or a proxy log — all of which the previous
 * {@code #result=<base64 tokens>} redirect exposed them to.
 *
 * <p>Deliberately in-memory and deliberately short-lived. Unlike a refresh token, a handoff code is
 * consumed within one redirect on the same host that issued it, so surviving a restart buys nothing:
 * the browser is already mid-flight and would simply retry the sign-in. That makes this the one
 * piece of auth state a {@code ConcurrentHashMap} genuinely suits.
 *
 * <p>Multi-instance note: the DPS must reach the <em>same</em> BFF instance that handled the
 * callback. Behind a load balancer that needs session affinity for {@code /api/auth/oauth/**}, or
 * this store moved to Redis alongside the refresh tokens.
 */
@Service
public class OAuthHandoffService {

    /**
     * Long enough for a redirect and one server-to-server call, short enough that a leaked code is
     * almost certainly already dead. This is not a session lifetime.
     */
    private static final Duration TTL = Duration.ofSeconds(60);

    private static final int MAX_PENDING = 10_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Handoff> pending = new ConcurrentHashMap<>();

    /**
     * Stores a completed sign-in and returns the code that redeems it.
     *
     * @param auth the tokens and user facts the DPS needs to build a session
     */
    public String store(AuthService.AuthResponse auth) {
        purgeExpired();
        if (pending.size() >= MAX_PENDING) {
            // A backstop, not an expected path: entries live 60 seconds, so reaching this means
            // something is generating codes far faster than they are being redeemed.
            throw new IllegalStateException("Too many pending OAuth handoffs");
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        pending.put(code, new Handoff(auth, Instant.now().plus(TTL)));
        return code;
    }

    /**
     * Redeems a code, returning the sign-in exactly once.
     *
     * <p>Single-use by construction: the entry is removed as it is read, so a replayed code — from a
     * log, a browser history entry, or an attacker who observed the redirect — finds nothing.
     */
    public Optional<AuthService.AuthResponse> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Handoff handoff = pending.remove(code);
        if (handoff == null || handoff.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(handoff.auth());
    }

    /** Codes are usually consumed; this sweeps the ones abandoned mid-flow. */
    private void purgeExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Handoff>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt().isBefore(now)) {
                iterator.remove();
            }
        }
    }

    private record Handoff(AuthService.AuthResponse auth, Instant expiresAt) {
    }
}
