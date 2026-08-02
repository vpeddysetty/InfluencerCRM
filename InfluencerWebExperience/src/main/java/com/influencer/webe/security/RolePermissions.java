package com.influencer.webe.security;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.influencer.webe.security.Permission.*;

/**
 * The single authoritative role → permission mapping.
 *
 * <p>Everything an authorization decision needs lives here, so changing what a role can do is one
 * edit rather than a sweep through controllers.
 *
 * <h2>Separation of duties</h2>
 * Two constraints are deliberate and must not be collapsed for convenience — they are the control
 * agencies get audited on:
 * <ul>
 *   <li>{@link AccountRole#MANAGER} may approve commissions but may not create or approve payouts.</li>
 *   <li>{@link AccountRole#FINANCE} owns the payout chain but cannot edit campaign or creator data.</li>
 * </ul>
 * The effect is that no single non-owner role can both create the obligation and settle it.
 */
public final class RolePermissions {

    /** Read-only surface shared by every role that can see a brand at all. */
    private static final Set<Permission> READ_ONLY = EnumSet.of(
            CREATOR_READ, CAMPAIGN_READ, WORKFLOW_READ, COUPON_READ,
            ATTRIBUTION_READ, COMMISSION_READ, PAYOUT_READ, CONTENT_READ, BRAND_READ);

    /** Day-to-day operational work: no money movement, no member or brand administration. */
    private static final Set<Permission> MARKETER_PERMISSIONS = union(READ_ONLY, EnumSet.of(
            CREATOR_WRITE, CAMPAIGN_WRITE, CAMPAIGN_CREATOR_ASSIGN,
            WORKFLOW_WRITE, COUPON_WRITE, CONTENT_WRITE,
            IMPORT_EXECUTE, IMPORT_UNDO));

    /** Everything a marketer can do, plus publishing, deletes, and commission approval. */
    private static final Set<Permission> MANAGER_PERMISSIONS = union(MARKETER_PERMISSIONS, EnumSet.of(
            CREATOR_DELETE, CAMPAIGN_DELETE, COUPON_PUSH,
            CONTENT_PUBLISH, MARKETPLACE_CONNECT,
            COMMISSION_APPROVE));

    /** The payout chain, plus the reads needed to justify a payment. No campaign or creator edits. */
    private static final Set<Permission> FINANCE_PERMISSIONS = union(READ_ONLY, EnumSet.of(
            COMMISSION_APPROVE, PAYOUT_CREATE, PAYOUT_APPROVE));

    /** Account administration on top of full brand control. */
    private static final Set<Permission> ADMIN_PERMISSIONS = union(MANAGER_PERMISSIONS, EnumSet.of(
            PAYOUT_CREATE, PAYOUT_APPROVE,
            BRAND_CREATE, BRAND_UPDATE, BRAND_DELETE,
            MEMBER_INVITE, MEMBER_UPDATE, MEMBER_REMOVE));

    /** Everything, including billing. */
    private static final Set<Permission> OWNER_PERMISSIONS = union(ADMIN_PERMISSIONS, EnumSet.of(
            ACCOUNT_BILLING));

    private static final Map<AccountRole, Set<Permission>> MATRIX = buildMatrix();

    private RolePermissions() {
    }

    private static Map<AccountRole, Set<Permission>> buildMatrix() {
        Map<AccountRole, Set<Permission>> matrix = new EnumMap<>(AccountRole.class);
        matrix.put(AccountRole.OWNER, OWNER_PERMISSIONS);
        matrix.put(AccountRole.ADMIN, ADMIN_PERMISSIONS);
        matrix.put(AccountRole.MANAGER, MANAGER_PERMISSIONS);
        matrix.put(AccountRole.MARKETER, MARKETER_PERMISSIONS);
        matrix.put(AccountRole.ANALYST, READ_ONLY);
        matrix.put(AccountRole.FINANCE, FINANCE_PERMISSIONS);
        return Map.copyOf(matrix);
    }

    public static Set<Permission> forRole(AccountRole role) {
        return role == null ? Set.of() : MATRIX.getOrDefault(role, Set.of());
    }

    /** Permission keys for the JWT {@code perms} claim. */
    public static Set<String> permissionKeys(AccountRole role) {
        return forRole(role).stream().map(Permission::key).collect(Collectors.toUnmodifiableSet());
    }

    public static boolean hasPermission(AccountRole role, Permission permission) {
        return forRole(role).contains(permission);
    }

    private static Set<Permission> union(Set<Permission> base, Set<Permission> extra) {
        EnumSet<Permission> combined = EnumSet.copyOf(base);
        combined.addAll(extra);
        return java.util.Collections.unmodifiableSet(combined);
    }
}
