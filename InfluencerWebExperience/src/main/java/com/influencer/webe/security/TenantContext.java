package com.influencer.webe.security;

import java.util.Set;
import java.util.UUID;

/**
 * The resolved, trusted identity of the caller for a single request.
 *
 * <p>Every field is derived from a verified JWT plus a validated {@code X-Brand-Id} header — never
 * from a request body or query parameter. Application code must take the tenancy key from
 * {@link #brandId()} and nowhere else.
 *
 * @param brandId            the ACTIVE brand for this request; the tenancy key for all data access
 * @param role               the caller's effective role <em>for {@code brandId}</em>, which may
 *                           differ per brand within one account
 * @param accessibleBrandIds every brand this caller may switch to
 */
public record TenantContext(
        UUID userId,
        UUID accountId,
        UUID brandId,
        String email,
        AccountRole role,
        Set<Permission> permissions,
        Set<UUID> accessibleBrandIds) {

    public boolean canAccessBrand(UUID candidateBrandId) {
        return candidateBrandId != null && accessibleBrandIds.contains(candidateBrandId);
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }

    /** Permission keys for the JWT claim and for Spring authorities. */
    public Set<String> permissionKeys() {
        return permissions.stream()
                .map(Permission::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Returns a copy scoped to a different brand, recomputing permissions for the role held there.
     * Callers must have already verified access to {@code newBrandId}.
     */
    public TenantContext withBrand(UUID newBrandId, AccountRole roleForBrand) {
        return new TenantContext(
                userId,
                accountId,
                newBrandId,
                email,
                roleForBrand,
                RolePermissions.forRole(roleForBrand),
                accessibleBrandIds);
    }
}
