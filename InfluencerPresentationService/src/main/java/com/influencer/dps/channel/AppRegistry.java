package com.influencer.dps.channel;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which micro-frontend is calling, and what it is entitled to (steps 1 and 2).
 *
 * <p><b>The gap this closes.</b> Every remote presenting a valid session cookie previously received
 * the whole session — all 33 permissions and every API path. So the content app, a screen for
 * writing captions, held {@code payout:approve} and {@code account:billing}. An XSS in any one
 * remote could drive every endpoint in the platform on the user's behalf, because nothing in the
 * chain knew which app the call came from.
 *
 * <p><b>An enum, not configuration.</b> Widening an app's reach is a security decision and should
 * appear in a diff and a review, not in a properties file that can be edited during an incident.
 * Same reasoning as {@code PlanPolicy}: things that change what someone may do belong in code.
 *
 * <p><b>Entitlements are an intersection, never a substitution.</b> An app cannot exceed what it
 * registers here, and registration cannot grant a user something their role does not already hold.
 * Both directions matter: the first contains a compromised frontend, the second stops this registry
 * from quietly becoming a second, competing permission model. The role matrix stays the authority
 * on what a person may do; this only narrows it per app.
 *
 * <p><b>Why paths as well as permissions.</b> Scoping the permission list alone would be theatre —
 * that list only decides what the UI renders, and hiding a button stops nobody. The path rules are
 * enforced on the proxy before a request is forwarded, which is what actually blocks the call.
 */
public enum AppRegistry {

    /**
     * The main shell. Deliberately broad: it hosts every route today, so narrowing it now would
     * break the product rather than secure it. It is also the app whose entitlement should shrink
     * as routes move out to the remotes below — the value here is that the reduction becomes
     * visible and reviewable rather than implicit.
     */
    SHELL("shell",
            Set.of("creator:read", "creator:write", "creator:delete",
                    "campaign:read", "campaign:write", "campaign:delete",
                    "campaign_creator:assign", "workflow:read", "workflow:write",
                    "coupon:read", "coupon:write", "coupon:push",
                    "attribution:read", "commission:read", "commission:approve",
                    "payout:read", "payout:create", "payout:approve",
                    "marketplace:connect", "content:read", "content:write", "content:publish",
                    "import:execute", "import:undo",
                    "brand:read", "brand:create", "brand:update", "brand:delete",
                    "member:invite", "member:update", "member:remove",
                    "account:billing", "account:billing:read"),
            List.of("/.*")),

    CREATORS_UI("creators-ui",
            Set.of("creator:read", "creator:write", "creator:delete",
                    "campaign:read", "campaign_creator:assign", "attribution:read"),
            List.of("/creators.*", "/campaign-creators.*", "/campaigns", "/analytics.*")),

    CAMPAIGNS_UI("campaigns-ui",
            Set.of("campaign:read", "campaign:write", "campaign:delete",
                    "campaign_creator:assign", "creator:read", "attribution:read"),
            List.of("/campaigns.*", "/campaign-creators.*", "/creators", "/analytics.*")),

    CONTENT_UI("content-ui",
            Set.of("content:read", "content:write", "content:publish",
                    "campaign:read", "creator:read"),
            List.of("/content.*", "/assets.*", "/campaigns", "/creators")),

    WORKFLOW_UI("workflow-ui",
            Set.of("workflow:read", "workflow:write", "creator:read", "campaign:read"),
            List.of("/workflow.*", "/creators", "/campaigns")),

    /**
     * Money out. Separated from commerce because approving a payout and connecting a store are
     * different jobs held by different people — the same separation of duties that keeps
     * {@code ACCOUNT_BILLING} owner-only while MANAGER may approve a commission.
     */
    FINANCE_UI("finance-ui",
            Set.of("payout:read", "payout:create", "payout:approve",
                    "commission:read", "commission:approve", "creator:read", "attribution:read"),
            List.of("/payouts.*", "/commissions.*", "/creators", "/analytics.*")),

    COMMERCE_UI("commerce-ui",
            Set.of("coupon:read", "coupon:write", "coupon:push",
                    "marketplace:connect", "attribution:read", "campaign:read", "creator:read"),
            List.of("/coupons.*", "/marketplace-connections.*", "/influencer-campaign-codes.*",
                    "/analytics.*", "/campaigns", "/creators"));

    private final String id;
    private final Set<String> allowedPermissions;
    private final List<Pattern> allowedPaths;

    AppRegistry(String id, Set<String> allowedPermissions, List<String> pathPatterns) {
        this.id = id;
        this.allowedPermissions = allowedPermissions;
        this.allowedPaths = pathPatterns.stream()
                // Anchored at both ends. An unanchored "/creators.*" would also match
                // "/payouts/creators", so a rule meant to permit one resource would silently
                // permit another that merely contains its name.
                .map(pattern -> Pattern.compile("^" + pattern + "$"))
                .toList();
    }

    public String id() {
        return id;
    }

    public Set<String> allowedPermissions() {
        return allowedPermissions;
    }

    public static Optional<AppRegistry> find(String appId) {
        if (appId == null || appId.isBlank()) {
            return Optional.empty();
        }
        String needle = appId.trim().toLowerCase();
        for (AppRegistry app : values()) {
            if (app.id.equals(needle)) {
                return Optional.of(app);
            }
        }
        return Optional.empty();
    }

    /**
     * Narrows a user's permissions to those this app is entitled to use.
     *
     * <p>Intersection in both directions — see the class note. A MARKETER loading finance-ui still
     * gets nothing they do not already hold.
     */
    public List<String> scope(List<String> userPermissions) {
        if (userPermissions == null) {
            return List.of();
        }
        return userPermissions.stream().filter(allowedPermissions::contains).toList();
    }

    /**
     * Whether this app may call an API path.
     *
     * <p>The query string is excluded before matching: it is caller-controlled and not part of the
     * resource's identity, so allowing it into the comparison would let {@code ?x=/payouts} affect
     * a decision it has no business affecting.
     */
    public boolean mayCall(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.split("\\?", 2)[0];
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (Pattern allowed : allowedPaths) {
            if (allowed.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }
}
