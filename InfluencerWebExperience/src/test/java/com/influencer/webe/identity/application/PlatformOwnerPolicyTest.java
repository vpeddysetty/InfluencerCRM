package com.influencer.webe.identity.application;

import com.influencer.webe.security.AccountRole;
import com.influencer.webe.security.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who reaches billing while pricing is undecided (case-study period, 2026-09).
 *
 * <p>Two properties carry the weight, and both are about failing the safe way. An unconfigured
 * deployment must close billing rather than open it — the opposite default would hand a checkout to
 * every account the first time someone forgot an environment variable. And the refusal is a 404:
 * a 403 confirms a billing system is there and says "not you", which is the question this whole
 * gate exists to avoid raising.
 */
class PlatformOwnerPolicyTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BRAND = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private TenantContext contextFor(String email) {
        return new TenantContext(USER, ACCOUNT, BRAND, email, AccountRole.OWNER, Set.of(), Set.of());
    }

    @Test
    @DisplayName("an unconfigured deployment lets NOBODY in, not everybody")
    void emptyMeansNobody() {
        // The failure that would matter: a missing environment variable handing a checkout to every
        // account, silently, on a product with no price.
        PlatformOwnerPolicy policy = new PlatformOwnerPolicy("");

        assertThat(policy.isPlatformOwner(contextFor("anyone@example.com"))).isFalse();
        assertThat(policy.isPlatformOwner(contextFor("vijay.peddysetty@kmpsglobal.com"))).isFalse();
    }

    @Test
    @DisplayName("the configured owner is admitted")
    void ownerIsAdmitted() {
        PlatformOwnerPolicy policy = new PlatformOwnerPolicy("vijay.peddysetty@kmpsglobal.com");

        assertThat(policy.isPlatformOwner(contextFor("vijay.peddysetty@kmpsglobal.com"))).isTrue();
    }

    @Test
    @DisplayName("anyone else is refused, including an account OWNER")
    void otherOwnersAreRefused() {
        // The distinction the whole class exists for: OWNER is a role inside a brand. Running the
        // platform is a different question, and every customer's account has an OWNER.
        PlatformOwnerPolicy policy = new PlatformOwnerPolicy("vijay.peddysetty@kmpsglobal.com");

        assertThat(policy.isPlatformOwner(contextFor("owner@somebrand.com"))).isFalse();
    }

    @Test
    @DisplayName("matching ignores case and surrounding whitespace")
    void matchingIsForgiving() {
        // The email column is free text; " Vijay@… " is a reachable stored state, and a gate that
        // failed on it would lock the operator out of their own billing.
        PlatformOwnerPolicy policy = new PlatformOwnerPolicy("  Vijay.Peddysetty@KMPSglobal.com  ");

        assertThat(policy.isPlatformOwner(contextFor("vijay.peddysetty@kmpsglobal.com"))).isTrue();
        assertThat(policy.isPlatformOwner(contextFor("  VIJAY.PEDDYSETTY@kmpsglobal.com "))).isTrue();
    }

    @Test
    @DisplayName("a second operator can be added without a code change")
    void supportsMoreThanOne() {
        PlatformOwnerPolicy policy = new PlatformOwnerPolicy("a@example.com, b@example.com");

        assertThat(policy.isPlatformOwner(contextFor("a@example.com"))).isTrue();
        assertThat(policy.isPlatformOwner(contextFor("b@example.com"))).isTrue();
        assertThat(policy.isPlatformOwner(contextFor("c@example.com"))).isFalse();
    }

    @Test
    @DisplayName("a context with no email is refused rather than matching a blank entry")
    void missingEmailIsRefused() {
        PlatformOwnerPolicy policy = new PlatformOwnerPolicy("vijay.peddysetty@kmpsglobal.com");

        assertThat(policy.isPlatformOwner(contextFor(null))).isFalse();
        assertThat(policy.isPlatformOwner(contextFor("   "))).isFalse();
        assertThat(policy.isPlatformOwner(null)).isFalse();
    }

    @Test
    @DisplayName("the refusal is 404, not 403")
    void refusalIsNotFound() {
        // 403 confirms the endpoint exists and says "you specifically may not", which on a surface
        // hidden because it is not on offer is an invitation to ask what is behind it.
        PlatformOwnerPolicy policy = new PlatformOwnerPolicy("vijay.peddysetty@kmpsglobal.com");

        assertThatThrownBy(() -> policy.requirePlatformOwner(contextFor("someone@else.com")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("the owner passes the guard without throwing")
    void ownerPassesTheGuard() {
        PlatformOwnerPolicy policy = new PlatformOwnerPolicy("vijay.peddysetty@kmpsglobal.com");

        policy.requirePlatformOwner(contextFor("vijay.peddysetty@kmpsglobal.com"));
    }
}
