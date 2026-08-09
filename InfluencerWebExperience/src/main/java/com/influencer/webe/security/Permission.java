package com.influencer.webe.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every capability the application can grant.
 *
 * <p>Application code checks <em>permissions</em>, never roles. Role checks scattered across call
 * sites are what make an authorization model impossible to change later; with this indirection,
 * adding a role or moving a capability is a single edit in {@link RolePermissions}.
 */
public enum Permission {

    CREATOR_READ("creator:read"),
    CREATOR_WRITE("creator:write"),
    CREATOR_DELETE("creator:delete"),

    CAMPAIGN_READ("campaign:read"),
    CAMPAIGN_WRITE("campaign:write"),
    CAMPAIGN_DELETE("campaign:delete"),

    CAMPAIGN_CREATOR_ASSIGN("campaign_creator:assign"),

    WORKFLOW_READ("workflow:read"),
    WORKFLOW_WRITE("workflow:write"),

    COUPON_READ("coupon:read"),
    COUPON_WRITE("coupon:write"),
    COUPON_PUSH("coupon:push"),

    ATTRIBUTION_READ("attribution:read"),

    COMMISSION_READ("commission:read"),
    COMMISSION_APPROVE("commission:approve"),

    PAYOUT_READ("payout:read"),
    PAYOUT_CREATE("payout:create"),
    PAYOUT_APPROVE("payout:approve"),

    MARKETPLACE_CONNECT("marketplace:connect"),

    CONTENT_READ("content:read"),
    CONTENT_WRITE("content:write"),
    CONTENT_PUBLISH("content:publish"),

    IMPORT_EXECUTE("import:execute"),
    IMPORT_UNDO("import:undo"),

    BRAND_READ("brand:read"),
    BRAND_CREATE("brand:create"),
    BRAND_UPDATE("brand:update"),
    BRAND_DELETE("brand:delete"),

    MEMBER_INVITE("member:invite"),
    MEMBER_UPDATE("member:update"),
    MEMBER_REMOVE("member:remove"),

    /**
     * See the subscription, its plan, and its invoices.
     *
     * <p>Split from {@link #ACCOUNT_BILLING} so an ADMIN can answer "what are we on and what did
     * we pay" without being able to end the subscription. Reading a plan is administration;
     * cancelling one stops the company's service, and those are not the same act.
     */
    ACCOUNT_BILLING_READ("account:billing:read"),

    /** Change the subscription — upgrade, pause, resume, cancel. OWNER only. */
    ACCOUNT_BILLING("account:billing");

    private final String key;

    Permission(String key) {
        this.key = key;
    }

    /** Stable wire form, used in JWT claims and Spring authorities. */
    public String key() {
        return key;
    }

    public static Optional<Permission> fromKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(p -> p.key.equals(key.trim())).findFirst();
    }
}
