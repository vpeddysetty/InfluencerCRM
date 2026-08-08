package com.influencer.webe.identity.application;

import java.util.Locale;

/**
 * What each plan is allowed (roadmap M2.3).
 *
 * <p><b>The gap this closes.</b> {@code accounts.plan} has existed since the Phase 2 tenancy
 * migration, defaults to {@code 'free'}, is stored, and is returned by the API — and nothing has
 * ever read it to make a decision. Every account has had unlimited everything regardless of plan.
 * That is not a missing feature so much as a column that lies: the API reports a plan that means
 * nothing.
 *
 * <p><b>Limits are per ACCOUNT, not per brand.</b> The account is the paying entity (see the
 * schema comment on {@code accounts}); an agency with three brands is one customer. Metering per
 * brand would let anyone multiply their allowance by creating brands, which is itself a metered
 * resource.
 *
 * <p><b>An enum rather than a table, deliberately.</b> Changing what a plan includes is a pricing
 * decision, and it should appear in a diff and go through review — the same reasoning as
 * {@code BrandDomainService.FREE_HOSTING}. When plans become customer-specific (a negotiated
 * enterprise deal), that is a table; until then a table is an unaudited way to change pricing.
 *
 * <p><b>Unknown plan names fall back to {@link #FREE}.</b> Never to unlimited: a typo in a plan
 * string, or a plan written by a future billing integration this code has not been taught about,
 * must not silently grant everything. Failing closed on entitlements is the same instinct as
 * failing closed on authorization.
 */
public enum PlanPolicy {

    /**
     * Limits sit deliberately ABOVE current real usage (measured 2026-08-07: max 2 brands,
     * 5 creators, 6 members, 2 landing pages in any one account). Enforcement blocks new
     * creation and never touches what exists, but a limit set below what customers already
     * have would freeze real accounts on the day it shipped, which is a support incident
     * rather than a monetization event.
     */
    FREE("free", 1, 25, 3, 3),

    /** Creator cap in the same range competitors meter at — see MARKET-ANALYSIS.md §2. */
    PRO("pro", 1, 250, 10, 25),

    /**
     * The multi-brand tier. Mirrors {@code account_type = 'agency'}, which already exists in the
     * schema, and is the tier the product's multi-brand tenancy is actually for.
     */
    // -1 is UNLIMITED. Written as a literal only because Java forbids an enum constant from
    // referring to a static field declared after it, and the field must follow the constants.
    AGENCY("agency", -1, -1, -1, -1);

    /** Sentinel for "no limit". -1 rather than MAX_VALUE so an accidental increment cannot wrap. */
    public static final int UNLIMITED = -1;

    private final String key;
    private final int maxBrands;
    private final int maxCreators;
    private final int maxMembers;
    private final int maxLandingPages;

    PlanPolicy(String key, int maxBrands, int maxCreators, int maxMembers, int maxLandingPages) {
        this.key = key;
        this.maxBrands = maxBrands;
        this.maxCreators = maxCreators;
        this.maxMembers = maxMembers;
        this.maxLandingPages = maxLandingPages;
    }

    public String key() {
        return key;
    }

    /**
     * The policy for a stored plan string.
     *
     * <p>Case- and whitespace-insensitive because the column is free text with no check
     * constraint, so {@code "Free"} and {@code " free "} are both reachable states.
     */
    public static PlanPolicy forKey(String plan) {
        if (plan == null || plan.isBlank()) {
            return FREE;
        }
        String normalized = plan.trim().toLowerCase(Locale.ROOT);
        for (PlanPolicy policy : values()) {
            if (policy.key.equals(normalized)) {
                return policy;
            }
        }
        // Fail closed. An unrecognised plan is likelier to be a typo or an unmigrated value than
        // a deliberate grant of everything.
        return FREE;
    }

    public int limitFor(Resource resource) {
        return switch (resource) {
            case BRAND -> maxBrands;
            case CREATOR -> maxCreators;
            case MEMBER -> maxMembers;
            case LANDING_PAGE -> maxLandingPages;
        };
    }

    /**
     * Whether one more of {@code resource} may be created given {@code currentCount}.
     *
     * <p>Compares {@code currentCount >= limit} rather than {@code >}: an account at exactly its
     * limit is full. Using {@code >} is the classic off-by-one that quietly grants one extra of
     * everything on every plan.
     */
    public boolean allows(Resource resource, long currentCount) {
        int limit = limitFor(resource);
        return limit == UNLIMITED || currentCount < limit;
    }

    /** A metered resource. */
    public enum Resource {
        BRAND("brand", "brands"),
        CREATOR("creator", "creators"),
        MEMBER("team member", "team members"),
        LANDING_PAGE("landing page", "landing pages");

        private final String singular;
        private final String plural;

        Resource(String singular, String plural) {
            this.singular = singular;
            this.plural = plural;
        }

        public String singular() {
            return singular;
        }

        public String plural() {
            return plural;
        }
    }
}
