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
     * Single-user. One person manages brands and creators; collaboration is the paid upgrade.
     *
     * <p><b>Why the member limit is 1 rather than 3.</b> The free tier's job is to let one person
     * run the workflow end to end and see the product work. Inviting colleagues is where a team
     * has adopted the tool, and that is the honest moment to charge. It is also the cleanest line
     * to explain: free is for you, paid is for your team.
     *
     * <p><b>Creators and brands are deliberately NOT reduced.</b> The wedge is spreadsheet
     * replacement, and a roster too small to hold a real creator list makes the product
     * unevaluable — which loses the trial rather than converting it.
     *
     * <p><b>This is the one limit that can bite an existing account.</b> Measured 2026-08-07, one
     * account already had 6 members. Enforcement blocks creation and never removes anyone, so that
     * account keeps its people and simply cannot invite more; its roles also keep working, because
     * {@link #allowsRoleBasedAccess()} is checked when assigning a role, not when honouring one.
     */
    FREE("free", 1, 25, 1, 3, 2),

    /** Creator cap in the same range competitors meter at — see MARKET-ANALYSIS.md §2. */
    PRO("pro", 1, 250, 10, 25, 20),

    /**
     * The multi-brand tier. Mirrors {@code account_type = 'agency'}, which already exists in the
     * schema, and is the tier the product's multi-brand tenancy is actually for.
     */
    // -1 is UNLIMITED. Written as a literal only because Java forbids an enum constant from
    // referring to a static field declared after it, and the field must follow the constants.
    AGENCY("agency", -1, -1, -1, -1, -1);

    /** Sentinel for "no limit". -1 rather than MAX_VALUE so an accidental increment cannot wrap. */
    public static final int UNLIMITED = -1;

    private final String key;
    private final int maxBrands;
    private final int maxCreators;
    private final int maxMembers;
    private final int maxLandingPages;

    /**
     * Saved page templates per brand (PR-39).
     *
     * <p><b>Why free gets a small allowance rather than none.</b> Saving a page shape you like and
     * reusing it is the moment the editor stops being a one-off tool, and a free tier that cannot
     * do it once never demonstrates the value being charged for. Two is enough to see the point
     * and not enough to run an agency on.
     *
     * <p><b>Why it is metered at all.</b> An unbounded per-brand jsonb table on a tier with no
     * revenue attached is a storage cost with no ceiling — the one shape of free-tier abuse that
     * costs real money rather than just capacity.
     */
    private final int maxSavedTemplates;

    PlanPolicy(String key, int maxBrands, int maxCreators, int maxMembers, int maxLandingPages,
               int maxSavedTemplates) {
        this.key = key;
        this.maxBrands = maxBrands;
        this.maxCreators = maxCreators;
        this.maxMembers = maxMembers;
        this.maxLandingPages = maxLandingPages;
        this.maxSavedTemplates = maxSavedTemplates;
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

    /**
     * Whether this plan may assign roles to team members.
     *
     * <p><b>A separate question from the seat count, on purpose.</b> It would be tempting to infer
     * it — "one seat, so no roles to assign" — but inference makes the rule invisible at the point
     * it is enforced, and it breaks the moment the free seat count changes for an unrelated reason.
     * Asking directly means the pricing decision is named in the code and a reviewer can see it.
     *
     * <p><b>It gates assignment, never enforcement.</b> An account that drops to free keeps the
     * roles its members already hold and the server keeps honouring them. Ignoring stored roles on
     * a downgrade would silently widen access — a MARKETER quietly becoming an owner is the worst
     * possible way to express "this feature is paid". What free loses is the ability to *change*
     * who has which role, not the protection roles provide.
     */
    public boolean allowsRoleBasedAccess() {
        return this != FREE;
    }

    public int limitFor(Resource resource) {
        return switch (resource) {
            case BRAND -> maxBrands;
            case CREATOR -> maxCreators;
            case MEMBER -> maxMembers;
            case LANDING_PAGE -> maxLandingPages;
            case SAVED_TEMPLATE -> maxSavedTemplates;
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

    /**
     * Whether {@code additional} more of {@code resource} may be created at once.
     *
     * <p>Exists because {@link #allows} only ever answers "may I create <em>one</em> more", and
     * calling it in a loop answers a different question than a batch asks: each call sees the same
     * stale count, so twenty calls all pass against one free seat.
     *
     * <p>Note {@code <=} where {@link #allows} uses {@code <}. Both mean the same thing — a batch
     * that lands exactly on the limit fills it, and one that lands a single row past it does not —
     * but the operator flips because this compares the count <em>after</em> the batch rather than
     * before it. Getting this backwards refuses the batch that exactly fits, which is the one an
     * admin is most likely to have sized deliberately.
     *
     * <p>{@code additional <= 0} is allowed rather than rejected: a batch whose rows were all
     * duplicates or existing members creates nothing, and refusing it would report a limit problem
     * for a request that needs no capacity at all.
     */
    public boolean allowsAdditional(Resource resource, long currentCount, long additional) {
        int limit = limitFor(resource);
        return limit == UNLIMITED || additional <= 0 || currentCount + additional <= limit;
    }

    /** A metered resource. */
    public enum Resource {
        BRAND("brand", "brands"),
        CREATOR("creator", "creators"),
        MEMBER("team member", "team members"),
        LANDING_PAGE("landing page", "landing pages"),
        SAVED_TEMPLATE("saved template", "saved templates");

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
