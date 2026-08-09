package com.influencer.webe.shared.workload;

import com.influencer.platform.observability.LogContext;

/**
 * The tenant this request was actually authorized for, as opposed to the one it asked for.
 *
 * <h2>Why this exists separately from the MDC tenant</h2>
 *
 * <p>{@code LogContext.TENANT} is populated by the request filter from the {@code X-Brand-Id}
 * header. That is correct for <em>logging</em> — you want to see what the caller asked for — but it
 * is a client-supplied value, and using it as the {@code tid} in a workload token would have signed
 * a claim the BFF never checked. The DAO would then trust it precisely because it was signed, which
 * is worse than not signing it at all: a forged header would have been laundered into evidence.
 *
 * <p>So the signed tenant is set here instead, and only by code that has already resolved the brand
 * through {@code RequestUserResolver} — which reads it from the verified JWT and calls
 * {@code canAccessBrand} before accepting an explicit override. By the time a value reaches this
 * class it has been checked against the token; that is what makes it safe to sign.
 *
 * <p><b>Deliberately not the same key as the log tenant.</b> Overwriting the MDC value would lose
 * the distinction between "what was requested" and "what was granted", and those differing is
 * exactly the event worth seeing in a log.
 */
public final class AuthoritativeTenant {

    /**
     * MDC key for the verified brand.
     *
     * <p>Held in MDC rather than a request attribute so it travels the same way the correlation id
     * does, and so {@code WorkloadTokenIssuer} can read it without every DAO call site having to
     * thread it through. The trade is the usual one: it does not follow work handed to another
     * thread, which is why {@code LogContext.wrap} exists.
     */
    public static final String KEY = "authTenant";

    private AuthoritativeTenant() {
    }

    /** Records a brand that has already been verified against the caller's token. */
    public static void set(String brandId) {
        if (brandId != null && !brandId.isBlank()) {
            org.slf4j.MDC.put(KEY, brandId);
        }
    }

    /** The verified brand, or null when this request never resolved one. */
    public static String get() {
        String value = org.slf4j.MDC.get(KEY);
        return value == null || value.isBlank() ? null : value;
    }

    public static void clear() {
        org.slf4j.MDC.remove(KEY);
    }

    /**
     * Whether the request's stated brand matches the verified one.
     *
     * <p>Used only for logging the divergence. The verified value is what is signed either way —
     * this exists so a mismatch is visible rather than silently corrected.
     */
    public static boolean matchesRequested() {
        String verified = get();
        String requested = LogContext.get(LogContext.TENANT);
        return verified == null || requested == null || verified.equals(requested);
    }
}
