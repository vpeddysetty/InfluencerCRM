package com.influencer.webe.security;

import com.influencer.webe.config.WebExperienceProperties;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.identity.application.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that tenancy can only come from a verified token, and that the tenancy key is the brand.
 *
 * <p>Guards two defects this migration closed:
 * <ul>
 *   <li>Phase 0: {@code RequestUserResolver} returned a caller-supplied {@code userId} whenever no
 *       token was present, so any client could read any tenant's data by naming them.</li>
 *   <li>Phase 2: with agencies, a caller who legitimately holds a token must still not be able to
 *       reach a brand outside their own account by naming its id.</li>
 * </ul>
 */
class CrossTenantIsolationTest {

    private static final UUID USER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ACCOUNT_A = UUID.fromString("acc00000-0000-0000-0000-00000000000a");
    private static final UUID BRAND_A1 = UUID.fromString("b0000000-0000-0000-0000-0000000000a1");
    private static final UUID BRAND_A2 = UUID.fromString("b0000000-0000-0000-0000-0000000000a2");

    /** Belongs to a different account entirely — must be unreachable from user A. */
    private static final UUID FOREIGN_BRAND = UUID.fromString("b0000000-0000-0000-0000-0000000000ff");

    private JwtService jwtService;
    private RequestUserResolver resolver;

    @BeforeEach
    void setUp() {
        WebExperienceProperties properties = new WebExperienceProperties();
        properties.setAccessTokenTtlMinutes(30);
        properties.setRefreshTokenTtlMinutes(1440);

        jwtService = new JwtService(properties);
        // SessionService is used here only for token verification, so the tenancy client is not
        // exercised; passing null keeps the test a unit test rather than requiring a live DAO.
        SessionService sessionService = new SessionService(jwtService, new RefreshTokenStore(properties), null);
        resolver = new RequestUserResolver(sessionService);
    }

    /** A token for an agency user who may reach two brands in their own account. */
    private String agencyBearer() {
        TenantContext context = new TenantContext(
                USER_A, ACCOUNT_A, BRAND_A1, "agency@example.com",
                AccountRole.ADMIN, RolePermissions.forRole(AccountRole.ADMIN),
                Set.of(BRAND_A1, BRAND_A2));
        return "Bearer " + jwtService.issueAccessToken(context, "password");
    }

    private String soloBearer(UUID userId, UUID brandId) {
        TenantContext context = new TenantContext(
                userId, brandId, brandId, "solo@example.com",
                AccountRole.OWNER, RolePermissions.forRole(AccountRole.OWNER), Set.of(brandId));
        return "Bearer " + jwtService.issueAccessToken(context, "password");
    }

    @Test
    @DisplayName("no token means 401 — a supplied id is never accepted as identity")
    void rejectsRequestWithoutToken() {
        assertThatThrownBy(() -> resolver.resolveBrandId(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("a blank or non-bearer Authorization header means 401")
    void rejectsMalformedAuthorizationHeader() {
        for (String header : new String[]{"", "   ", "Basic dXNlcjpwYXNz", "token abc", "Bearer "}) {
            assertThatThrownBy(() -> resolver.resolveBrandId(header))
                    .as("header %s must not authenticate", header)
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    @DisplayName("a forged token means 401 even when a real brand id is supplied alongside it")
    void rejectsForgedToken() {
        assertThatThrownBy(() -> resolver.resolveBrandId("Bearer forged-token", BRAND_A1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("a brand outside the caller's account is 403 — never that brand's data")
    void rejectsForeignBrand() {
        assertThatThrownBy(() -> resolver.resolveBrandId(agencyBearer(), FOREIGN_BRAND))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("a solo brand user cannot reach another tenant's brand")
    void soloUserCannotReachAnotherTenant() {
        UUID brandB = UUID.fromString("b0000000-0000-0000-0000-0000000000b1");
        assertThatThrownBy(() -> resolver.resolveBrandId(soloBearer(USER_A, BRAND_A1), brandB))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("an agency user may name any brand within their own account")
    void agencyUserMaySwitchWithinAccount() {
        String bearer = agencyBearer();
        assertThat(resolver.resolveBrandId(bearer, BRAND_A1)).isEqualTo(BRAND_A1);
        assertThat(resolver.resolveBrandId(bearer, BRAND_A2)).isEqualTo(BRAND_A2);
    }

    @Test
    @DisplayName("with no brand named, the token's active brand is used")
    void defaultsToTokenBrand() {
        assertThat(resolver.resolveBrandId(agencyBearer())).isEqualTo(BRAND_A1);
        assertThat(resolver.resolveBrandId(agencyBearer(), null)).isEqualTo(BRAND_A1);
    }

    @Test
    @DisplayName("each tenant's token resolves only to that tenant's brand")
    void tokensResolveToTheirOwnBrand() {
        UUID brandB = UUID.fromString("b0000000-0000-0000-0000-0000000000b1");
        assertThat(resolver.resolveBrandId(soloBearer(USER_A, BRAND_A1))).isEqualTo(BRAND_A1);
        assertThat(resolver.resolveBrandId(soloBearer(USER_B, brandB))).isEqualTo(brandB);
    }

    @Test
    @DisplayName("identity still comes from the token: a mismatched userId is 403")
    void identityAlwaysComesFromTheToken() {
        String bearer = soloBearer(USER_A, BRAND_A1);
        assertThat(resolver.resolveUserId(bearer)).isEqualTo(USER_A);
        assertThat(resolver.resolveUserId(bearer, USER_A)).isEqualTo(USER_A);

        assertThatThrownBy(() -> resolver.resolveUserId(bearer, USER_B))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("a permission the caller's role lacks is 403, not a silent pass")
    void enforcesPermissions() {
        // An ANALYST holds only read permissions.
        TenantContext analyst = new TenantContext(
                USER_A, ACCOUNT_A, BRAND_A1, "analyst@example.com",
                AccountRole.ANALYST, RolePermissions.forRole(AccountRole.ANALYST), Set.of(BRAND_A1));
        String bearer = "Bearer " + jwtService.issueAccessToken(analyst, "password");

        assertThat(resolver.requirePermissionForBrand(bearer, Permission.CREATOR_READ)).isEqualTo(BRAND_A1);

        assertThatThrownBy(() -> resolver.requirePermissionForBrand(bearer, Permission.CREATOR_WRITE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> resolver.requirePermissionForBrand(bearer, Permission.PAYOUT_CREATE))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("a MANAGER cannot create payouts — separation of duties survives to the call site")
    void managerCannotCreatePayouts() {
        TenantContext manager = new TenantContext(
                USER_A, ACCOUNT_A, BRAND_A1, "manager@example.com",
                AccountRole.MANAGER, RolePermissions.forRole(AccountRole.MANAGER), Set.of(BRAND_A1));
        String bearer = "Bearer " + jwtService.issueAccessToken(manager, "password");

        // Approving a commission is within a manager's remit...
        assertThat(resolver.requirePermissionForBrand(bearer, Permission.COMMISSION_APPROVE))
                .isEqualTo(BRAND_A1);

        // ...but settling it is not.
        assertThatThrownBy(() -> resolver.requirePermissionForBrand(bearer, Permission.PAYOUT_CREATE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
