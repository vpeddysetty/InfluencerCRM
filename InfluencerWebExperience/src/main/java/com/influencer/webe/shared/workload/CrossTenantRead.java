package com.influencer.webe.shared.workload;

/**
 * Marks the narrow window in which a background job may read across every tenant.
 *
 * <h2>Why this is a scope and not a path exemption</h2>
 *
 * <p>{@code HostingExpiryScheduler} sweeps all brands' landing pages to warn owners before hosting
 * ends. It runs on a timer, so there is no user, no request, and no brand to sign — and once the
 * DAO enforces tenant scoping it would simply start failing, with the only symptom being that
 * warning emails quietly stopped. A silent scheduler is the worst kind of broken.
 *
 * <p>The easy fix would have been to exempt {@code /landing-templates} in the DAO's filter. That is
 * wrong: the same path serves ordinary user requests, so the exemption would reopen the hole for
 * exactly the traffic the filter exists to close. A scope travels with the caller and is proved by
 * a signature, so only this sweep can use it.
 *
 * <h2>Scoped to a block, never left on</h2>
 *
 * <p>{@link #runAsSweep} clears the flag in a {@code finally}. Leaving it set would grant
 * cross-tenant reads to whatever else ran on that pooled thread next, which is a worse hole than
 * the one being closed — and it would be invisible, because everything would keep working.
 */
public final class CrossTenantRead {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private CrossTenantRead() {
    }

    /** Whether the current thread is inside a sweep. */
    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /**
     * Runs {@code work} with cross-tenant read permission.
     *
     * <p>Deliberately takes the work rather than exposing set/clear: a caller cannot forget to turn
     * it off, because there is no way to turn it on without also scheduling the turning off.
     */
    public static void runAsSweep(Runnable work) {
        boolean alreadyActive = isActive();
        ACTIVE.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            if (alreadyActive) {
                ACTIVE.set(Boolean.TRUE);
            } else {
                // remove() rather than set(false): a lingering FALSE on a pooled thread is
                // harmless but keeps the entry alive, and ThreadLocals on pooled threads are how
                // slow leaks start.
                ACTIVE.remove();
            }
        }
    }
}
