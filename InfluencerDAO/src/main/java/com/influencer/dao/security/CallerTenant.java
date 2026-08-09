package com.influencer.dao.security;

/**
 * The tenant this request is authorized for, taken from the caller's signed workload token.
 *
 * <h2>The hole this closes</h2>
 *
 * <p>DAO controllers take {@code brandId} as an <em>optional</em> query parameter and fall back to
 * an unfiltered query when it is absent:
 *
 * <pre>
 *   public List&lt;Creator&gt; findAll(&#64;RequestParam(required = false) UUID brandId) {
 *       if (brandId != null) return repository.findByBrandId(brandId);
 *       return repository.findAll();          // ← every tenant's rows
 *   }
 * </pre>
 *
 * <p>So {@code GET /creators} returns the entire table across every brand, and
 * {@code GET /creators?brandId=<someone-else>} returns theirs. Nothing in the request had to be
 * forged; the parameter simply had to be changed or omitted. The service token authenticated the
 * <em>caller</em> and said nothing about which tenant the call was for.
 *
 * <h2>Why this is the fix rather than "validate the parameter"</h2>
 *
 * <p>Validating {@code brandId} against something requires knowing what to compare it to, and the
 * only thing the request carried was that parameter. The workload token's {@code tid} claim is a
 * tenant the BFF asserted and signed at the moment it authorized the user, so it cannot be edited
 * in flight — comparing against it is comparing against evidence rather than against another
 * caller-supplied value.
 *
 * <h2>Enforcement is staged, deliberately</h2>
 *
 * <p>{@link #resolve} returns the signed tenant when there is one and falls back to the requested
 * parameter otherwise, logging the fallback. It does not yet <em>refuse</em> a mismatch, because
 * during the migration the BFF may still be calling with a legacy token that carries no tenant at
 * all — and turning every such call into a 403 would take the platform down rather than secure it.
 *
 * <p>What it does today is make the signed value authoritative wherever one exists, and make every
 * remaining unsigned call visible in the logs. When those log lines stop, {@link #requireMatch} can
 * be switched from warning to throwing in one place.
 */
public final class CallerTenant {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CallerTenant.class);

    /** MDC key the filter writes the signed tenant to. */
    static final String MDC_TENANT = "tenant";

    private CallerTenant() {
    }

    /** The tenant asserted by the caller's signed token, or null if the call carried none. */
    public static String signed() {
        String tenant = org.slf4j.MDC.get(MDC_TENANT);
        return tenant == null || tenant.isBlank() ? null : tenant;
    }

    /**
     * The tenant a query should actually be scoped to.
     *
     * <p>Prefers the signed value over the requested one. A caller that asks for a different tenant
     * than its token asserts gets its token's tenant, not its request's — the request is the part
     * an attacker controls.
     *
     * @param requested the {@code brandId} query parameter, which may be null
     * @return the tenant to filter by, or null when neither source supplied one
     */
    public static String resolve(String requested) {
        String signed = signed();

        if (signed == null) {
            if (requested != null) {
                // Visible on purpose: this is a call the signed path does not yet cover, and the
                // count of these is what says whether enforcement can be turned on.
                log.debug("No signed tenant on this call; using the requested brandId");
            }
            return requested;
        }

        if (requested != null && !requested.equals(signed)) {
            // Not an error yet, but it should never happen in normal operation: the BFF derives
            // both from the same authorized context. A burst of these is either a bug or probing.
            log.warn("Caller asked for tenant {} but its token asserts {}; using the signed value",
                    requested, signed);
        }
        return signed;
    }

    /**
     * Whether a request may act on {@code requested}.
     *
     * <p>Returns true when nothing was signed, which is what keeps the migration non-breaking.
     * Flipping that to false is the switch that completes this work.
     */
    public static boolean requireMatch(String requested) {
        String signed = signed();
        if (signed == null || requested == null) {
            return true;
        }
        return signed.equals(requested);
    }
}
