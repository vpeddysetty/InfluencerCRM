package com.influencer.webe.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * Account- and brand-level roles, mirroring the Postgres {@code account_role} enum.
 *
 * <p>Scope is documented per constant because it drives where the role can be granted: account-level
 * roles come from {@code memberships.role}, brand-level roles from {@code brand_access.role}.
 */
public enum AccountRole {

    /** Account. Billing, delete account, manage admins. Exactly one required per account. */
    OWNER,

    /** Account. Manage brands, invite/remove members, reach all brand data. */
    ADMIN,

    /** Brand. Full control of assigned brands, including approving commissions. */
    MANAGER,

    /** Brand. Day-to-day campaigns, creators, outreach, content. No financial approval. */
    MARKETER,

    /** Brand. Read-only plus exports — the safe default for contractors. */
    ANALYST,

    /** Account. Commissions and payouts across all brands. Cannot edit campaign data. */
    FINANCE;

    /**
     * Roles that reach every brand in their account without an explicit {@code brand_access} row.
     *
     * <p>FINANCE is account-wide because commissions and payouts settle across the whole account;
     * a finance user pinned to a single brand could not do their job. Must stay in step with the
     * same rule in {@code BrandRepository.findAccessibleBrands} — if the two disagree, a user can
     * log in seeing one set of brands and be refused on another.
     */
    public boolean impliesAllBrands() {
        return this == OWNER || this == ADMIN || this == FINANCE;
    }

    public static Optional<AccountRole> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase();
        return Arrays.stream(values()).filter(role -> role.name().equals(normalized)).findFirst();
    }
}
