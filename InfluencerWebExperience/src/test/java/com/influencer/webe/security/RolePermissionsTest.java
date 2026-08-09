package com.influencer.webe.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.influencer.webe.security.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the role → permission matrix from docs/architecture-migration-plan.md §4.2.
 *
 * <p>The matrix is the whole authorization model, so it is asserted cell by cell rather than
 * spot-checked: a silent widening here grants capabilities across every endpoint at once.
 */
class RolePermissionsTest {

    @Nested
    @DisplayName("separation of duties")
    class SeparationOfDuties {

        @Test
        @DisplayName("MANAGER approves commissions but cannot create or approve payouts")
        void managerCannotMovePayouts() {
            assertThat(RolePermissions.hasPermission(AccountRole.MANAGER, COMMISSION_APPROVE)).isTrue();
            assertThat(RolePermissions.hasPermission(AccountRole.MANAGER, PAYOUT_CREATE)).isFalse();
            assertThat(RolePermissions.hasPermission(AccountRole.MANAGER, PAYOUT_APPROVE)).isFalse();
        }

        @Test
        @DisplayName("FINANCE owns the payout chain but cannot edit campaign or creator data")
        void financeCannotEditOperationalData() {
            assertThat(RolePermissions.hasPermission(AccountRole.FINANCE, PAYOUT_CREATE)).isTrue();
            assertThat(RolePermissions.hasPermission(AccountRole.FINANCE, PAYOUT_APPROVE)).isTrue();
            assertThat(RolePermissions.hasPermission(AccountRole.FINANCE, COMMISSION_APPROVE)).isTrue();

            assertThat(RolePermissions.hasPermission(AccountRole.FINANCE, CAMPAIGN_WRITE)).isFalse();
            assertThat(RolePermissions.hasPermission(AccountRole.FINANCE, CREATOR_WRITE)).isFalse();
            assertThat(RolePermissions.hasPermission(AccountRole.FINANCE, COUPON_WRITE)).isFalse();
            assertThat(RolePermissions.hasPermission(AccountRole.FINANCE, IMPORT_EXECUTE)).isFalse();
        }

        @Test
        @DisplayName("no non-owner role can both create an obligation and settle it alone")
        void noSingleRoleOwnsTheWholeMoneyPath() {
            // MARKETER creates the commercial obligation (coupons/campaigns) but touches no money.
            assertThat(RolePermissions.hasPermission(AccountRole.MARKETER, COUPON_WRITE)).isTrue();
            assertThat(RolePermissions.hasPermission(AccountRole.MARKETER, COMMISSION_APPROVE)).isFalse();
            assertThat(RolePermissions.hasPermission(AccountRole.MARKETER, PAYOUT_CREATE)).isFalse();
        }
    }

    @Nested
    @DisplayName("ANALYST is strictly read-only")
    class AnalystIsReadOnly {

        @Test
        @DisplayName("has every read permission")
        void hasReads() {
            assertThat(RolePermissions.forRole(AccountRole.ANALYST))
                    .contains(CREATOR_READ, CAMPAIGN_READ, WORKFLOW_READ, COUPON_READ,
                            ATTRIBUTION_READ, COMMISSION_READ, PAYOUT_READ, CONTENT_READ, BRAND_READ);
        }

        @Test
        @DisplayName("has no permission that mutates, publishes, or moves money")
        void hasNoWrites() {
            assertThat(RolePermissions.forRole(AccountRole.ANALYST))
                    .allSatisfy(permission -> assertThat(permission.key())
                            .as("ANALYST must hold only read permissions but has %s", permission.key())
                            .endsWith(":read"));
        }
    }

    @Nested
    @DisplayName("account administration")
    class AccountAdministration {

        @Test
        @DisplayName("only OWNER can change billing")
        void onlyOwnerBills() {
            // Unchanged by M2.1/M2.2. Pausing or cancelling stops the company's service, and an
            // invited admin must not be able to do that to the person who owns the account.
            for (AccountRole role : AccountRole.values()) {
                assertThat(RolePermissions.hasPermission(role, ACCOUNT_BILLING))
                        .as("%s billing access", role)
                        .isEqualTo(role == AccountRole.OWNER);
            }
        }

        @Test
        @DisplayName("OWNER and ADMIN can see billing, but only OWNER can change it")
        void adminSeesBillingWithoutControllingIt() {
            // The split added for the subscription module. An admin administers the account and
            // needs to know what it is on and what it has paid; that is not the same as being able
            // to end it. Same separation-of-duties instinct as MANAGER approving commissions
            // without settling them.
            for (AccountRole role : AccountRole.values()) {
                boolean canSee = role == AccountRole.OWNER || role == AccountRole.ADMIN;
                assertThat(RolePermissions.hasPermission(role, ACCOUNT_BILLING_READ))
                        .as("%s billing read", role).isEqualTo(canSee);
            }

            assertThat(RolePermissions.hasPermission(AccountRole.ADMIN, ACCOUNT_BILLING_READ)).isTrue();
            assertThat(RolePermissions.hasPermission(AccountRole.ADMIN, ACCOUNT_BILLING)).isFalse();
        }

        @Test
        @DisplayName("anyone who can change billing can also see it")
        void writeImpliesRead() {
            // A role able to cancel but not to view what it is cancelling would be a UI that has
            // to guess, and the two permissions could drift apart unnoticed.
            for (AccountRole role : AccountRole.values()) {
                if (RolePermissions.hasPermission(role, ACCOUNT_BILLING)) {
                    assertThat(RolePermissions.hasPermission(role, ACCOUNT_BILLING_READ))
                            .as("%s can change billing but not read it", role).isTrue();
                }
            }
        }

        @Test
        @DisplayName("only OWNER and ADMIN manage members and brands")
        void onlyAdminsManageMembership() {
            for (AccountRole role : AccountRole.values()) {
                boolean expected = role == AccountRole.OWNER || role == AccountRole.ADMIN;
                assertThat(RolePermissions.hasPermission(role, MEMBER_INVITE))
                        .as("%s member:invite", role).isEqualTo(expected);
                assertThat(RolePermissions.hasPermission(role, BRAND_CREATE))
                        .as("%s brand:create", role).isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("OWNER is a strict superset of ADMIN, which is a superset of MANAGER")
        void rolesNest() {
            assertThat(RolePermissions.forRole(AccountRole.OWNER))
                    .containsAll(RolePermissions.forRole(AccountRole.ADMIN));
            assertThat(RolePermissions.forRole(AccountRole.ADMIN))
                    .containsAll(RolePermissions.forRole(AccountRole.MANAGER));
            assertThat(RolePermissions.forRole(AccountRole.MANAGER))
                    .containsAll(RolePermissions.forRole(AccountRole.MARKETER));
            assertThat(RolePermissions.forRole(AccountRole.MARKETER))
                    .containsAll(RolePermissions.forRole(AccountRole.ANALYST));
        }
    }

    @Test
    @DisplayName("account-wide roles reach every brand; brand-scoped roles do not")
    void brandWideRoles() {
        // OWNER, ADMIN and FINANCE are account-level roles.
        assertThat(AccountRole.OWNER.impliesAllBrands()).isTrue();
        assertThat(AccountRole.ADMIN.impliesAllBrands()).isTrue();
        // FINANCE settles commissions and payouts across the whole account. Treating it as
        // brand-scoped left finance users with zero accessible brands and therefore unable to
        // log in at all — caught end-to-end, not by the unit tests, hence this guard.
        assertThat(AccountRole.FINANCE.impliesAllBrands()).isTrue();

        assertThat(AccountRole.MANAGER.impliesAllBrands()).isFalse();
        assertThat(AccountRole.MARKETER.impliesAllBrands()).isFalse();
        assertThat(AccountRole.ANALYST.impliesAllBrands()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AccountRole.class)
    @DisplayName("every role can read the brand it is scoped to")
    void everyRoleCanRead(AccountRole role) {
        assertThat(RolePermissions.hasPermission(role, BRAND_READ)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AccountRole.class)
    @DisplayName("permission keys survive a round trip through the JWT claim form")
    void permissionKeysRoundTrip(AccountRole role) {
        for (String key : RolePermissions.permissionKeys(role)) {
            assertThat(Permission.fromKey(key))
                    .as("permission key %s must parse back", key)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("an unknown permission key is rejected rather than silently granted")
    void unknownKeysRejected() {
        assertThat(Permission.fromKey("payout:approve-everything")).isEmpty();
        assertThat(Permission.fromKey("")).isEmpty();
        assertThat(Permission.fromKey(null)).isEmpty();
    }
}
