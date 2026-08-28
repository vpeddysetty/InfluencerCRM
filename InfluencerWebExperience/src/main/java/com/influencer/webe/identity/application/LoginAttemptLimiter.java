package com.influencer.webe.identity.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refuses repeated failed sign-ins before the password is ever checked (roadmap PR-43).
 *
 * <p><b>Why the check has to come BEFORE the BCrypt comparison.</b> BCrypt is deliberately slow —
 * roughly 100ms per verification, which is the property that makes it a good password hash. That
 * same property makes an unthrottled login endpoint a denial-of-service amplifier: a few hundred
 * concurrent guesses saturate the request threads with work the server chose to make expensive,
 * and the site goes down for everyone while the attacker learns nothing. Rejecting a limited caller
 * before the hash means a refused attempt costs a map lookup rather than 100ms of CPU.
 *
 * <p><b>Keyed on the email, not the IP.</b> Two different threats, and this is the one that
 * matters here: a creator's account is what an attacker wants, and they will spread guesses across
 * addresses to defeat IP limits anyway. Keying on the account being attacked protects the account
 * regardless of where the guesses come from. The cost is that somebody can lock a creator out by
 * guessing at their address on purpose — accepted deliberately, because the window is short and
 * the alternative is leaving the account undefended.
 *
 * <p><b>In-memory, and honestly so.</b> One instance serves production today, so a map is the
 * whole mechanism. It resets on deploy, which is a real weakness — an attacker who noticed could
 * wait out a release — but a shared store for this before there is a second instance would be
 * infrastructure ahead of need. When the portal has real traffic this moves to the same table the
 * sessions did, and PR-40's note about the ASG rolling on every deploy applies here too.
 */
@Component
public class LoginAttemptLimiter {

    /**
     * Five, then a pause.
     *
     * <p>High enough that a person mistyping a password twice and then getting it right is never
     * stopped; low enough that a guessing loop gets almost nowhere. The point is not to make
     * guessing impossible but to make it slower than it is worth.
     */
    private static final int MAX_FAILURES = 5;

    /** How long a locked address stays locked. */
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    /**
     * How long a failure counts against an address.
     *
     * <p>Longer than the lockout, so five failures spread across an hour still trip it. A window
     * equal to the lockout would let a patient attacker guess indefinitely at four-per-window.
     */
    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    /** True when this address may attempt a sign-in right now. */
    public boolean allow(String key) {
        Attempts current = attempts.get(normalize(key));
        if (current == null) {
            return true;
        }
        if (current.lockedUntil != null && current.lockedUntil.isAfter(Instant.now())) {
            return false;
        }
        return true;
    }

    /** Record a failed attempt, locking the address once it has failed enough times. */
    public void recordFailure(String key) {
        String id = normalize(key);
        Instant now = Instant.now();
        attempts.compute(id, (ignored, existing) -> {
            if (existing == null || existing.firstFailureAt.plus(WINDOW).isBefore(now)) {
                return new Attempts(1, now, null);
            }
            int failures = existing.failures + 1;
            Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCKOUT) : existing.lockedUntil;
            return new Attempts(failures, existing.firstFailureAt, lockedUntil);
        });
    }

    /**
     * Clear the record after a successful sign-in.
     *
     * <p>So somebody who mistyped four times and then remembered their password starts clean,
     * rather than being one typo away from a lockout for the next hour.
     */
    public void recordSuccess(String key) {
        attempts.remove(normalize(key));
    }

    private String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record Attempts(int failures, Instant firstFailureAt, Instant lockedUntil) {
    }
}
