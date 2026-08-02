package com.influencer.webe.security;


import java.util.Optional;

/**
 * Makes the current request's {@link TenantContext} reachable from code that has no access to the
 * HTTP request — notably the outbound {@code DaoGatewayClient}, which must stamp the tenancy key on
 * every downstream call.
 *
 * <p>Set by {@link JwtAuthenticationFilter} and always cleared in its {@code finally} block, since
 * servlet threads are pooled and a stale value would leak one caller's tenancy into the next
 * request.
 */
public final class TenantContextHolder {
    public static final String REQUEST_ATTRIBUTE = "influencrm.tenantContext";

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void set(TenantContext context) {
        CURRENT.set(context);
    }

    public static Optional<TenantContext> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Throws when no authenticated context is present — use where a tenant is mandatory. */
    public static TenantContext require() {
        TenantContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("No authenticated tenant context is bound to this request");
        }
        return context;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
