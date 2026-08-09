package com.influencer.dps.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-app entitlement (roadmap steps 1 and 2).
 *
 * <p>The gap being closed: every remote presenting a valid session cookie received the whole
 * session — all 33 permissions and every API path — so an XSS in the content app could drive
 * payouts on the user's behalf.
 */
class AppRegistryTest {

    /** What an OWNER holds. Verified against a live session during development. */
    private static final List<String> OWNER = List.of(
            "creator:read", "creator:write", "creator:delete",
            "campaign:read", "campaign:write", "campaign:delete",
            "workflow:read", "workflow:write",
            "coupon:read", "coupon:write", "coupon:push",
            "attribution:read", "commission:read", "commission:approve",
            "payout:read", "payout:create", "payout:approve",
            "marketplace:connect", "content:read", "content:write", "content:publish",
            "import:execute", "import:undo",
            "brand:read", "brand:create", "brand:update", "brand:delete",
            "member:invite", "member:update", "member:remove",
            "account:billing", "account:billing:read", "campaign_creator:assign");

    // ---- the attack this exists to stop ---------------------------------

    @Test
    @DisplayName("the content app cannot reach payouts or billing")
    void contentAppIsConfined() {
        AppRegistry content = AppRegistry.CONTENT_UI;

        // The specific scenario: XSS in a captions screen driving money movement.
        assertFalse(content.mayCall("/payouts"));
        assertFalse(content.mayCall("/payouts/123/approve"));
        assertFalse(content.mayCall("/billing/subscription"));
        assertFalse(content.mayCall("/brands/members"));

        List<String> scoped = content.scope(OWNER);
        assertFalse(scoped.contains("payout:approve"), "an OWNER's payout rights must not reach content-ui");
        assertFalse(scoped.contains("account:billing"));
        assertTrue(scoped.contains("content:write"), "but its own job must still work");
    }

    @Test
    @DisplayName("the finance app cannot publish content")
    void financeAppIsConfined() {
        // Separation of duties in the other direction — approving a payout and publishing a post
        // are different jobs, and neither app should be able to do the other's.
        AppRegistry finance = AppRegistry.FINANCE_UI;

        assertFalse(finance.scope(OWNER).contains("content:publish"));
        assertFalse(finance.mayCall("/content/posts"));
        assertTrue(finance.mayCall("/payouts"));
        assertTrue(finance.scope(OWNER).contains("payout:approve"));
    }

    // ---- the intersection rule ------------------------------------------

    @Test
    @DisplayName("registration cannot grant a permission the user does not hold")
    void cannotWidenBeyondTheUser() {
        // Otherwise this registry quietly becomes a second permission model competing with the
        // role matrix — and the two disagreeing is what once locked FINANCE users out entirely.
        List<String> marketer = List.of("content:read", "content:write", "campaign:read");

        List<String> scoped = AppRegistry.CONTENT_UI.scope(marketer);

        assertFalse(scoped.contains("content:publish"), "the app allows it; the user does not hold it");
        assertEquals(3, scoped.size());
    }

    @Test
    @DisplayName("an app cannot exceed what it registered")
    void cannotWidenBeyondTheApp() {
        assertFalse(AppRegistry.WORKFLOW_UI.scope(OWNER).contains("brand:delete"));
    }

    @Test
    @DisplayName("a null or empty permission list is handled")
    void handlesEmptyPermissions() {
        assertTrue(AppRegistry.CONTENT_UI.scope(null).isEmpty());
        assertTrue(AppRegistry.CONTENT_UI.scope(List.of()).isEmpty());
    }

    // ---- path matching ---------------------------------------------------

    @Test
    @DisplayName("path rules are anchored, so a prefix cannot smuggle another resource")
    void pathsAreAnchored() {
        // An unanchored "/creators.*" would also match "/payouts/creators", turning a rule meant
        // to permit one resource into one that permits another that merely contains its name.
        assertTrue(AppRegistry.CREATORS_UI.mayCall("/creators"));
        assertTrue(AppRegistry.CREATORS_UI.mayCall("/creators/abc-123"));
        assertFalse(AppRegistry.CREATORS_UI.mayCall("/payouts/creators"));
        assertFalse(AppRegistry.CREATORS_UI.mayCall("/x/creators"));
    }

    @Test
    @DisplayName("the query string cannot influence the decision")
    void ignoresTheQueryString() {
        // It is caller-controlled and not part of the resource's identity.
        assertTrue(AppRegistry.CREATORS_UI.mayCall("/creators?brandId=1"));
        assertFalse(AppRegistry.CREATORS_UI.mayCall("/payouts?x=/creators"));
    }

    @Test
    @DisplayName("a trailing slash does not change the answer")
    void normalisesTrailingSlash() {
        assertTrue(AppRegistry.CREATORS_UI.mayCall("/creators/"));
    }

    @Test
    @DisplayName("a null or blank path is refused")
    void refusesEmptyPaths() {
        assertFalse(AppRegistry.CONTENT_UI.mayCall(null));
        assertFalse(AppRegistry.CONTENT_UI.mayCall(""));
    }

    // ---- lookup ----------------------------------------------------------

    @Test
    @DisplayName("app ids resolve case-insensitively and unknown ones do not resolve")
    void resolvesIds() {
        assertTrue(AppRegistry.find("content-ui").isPresent());
        assertTrue(AppRegistry.find("CONTENT-UI").isPresent());
        assertTrue(AppRegistry.find(" content-ui ").isPresent());
        assertTrue(AppRegistry.find("does-not-exist").isEmpty());
        assertTrue(AppRegistry.find(null).isEmpty());
        assertTrue(AppRegistry.find("").isEmpty());
    }

    @Test
    @DisplayName("the shell keeps full reach, deliberately")
    void shellIsBroad() {
        // It hosts every route today. Narrowing it now would break the product rather than secure
        // it; the value is that any future reduction becomes visible in a diff.
        assertEquals(OWNER.size(), AppRegistry.SHELL.scope(OWNER).size());
        assertTrue(AppRegistry.SHELL.mayCall("/payouts"));
        assertTrue(AppRegistry.SHELL.mayCall("/anything/at/all"));
    }

    @Test
    @DisplayName("every registered app id is unique")
    void idsAreUnique() {
        // Two apps sharing an id would make lookup order decide entitlement.
        long distinct = java.util.Arrays.stream(AppRegistry.values())
                .map(AppRegistry::id).distinct().count();

        assertEquals(AppRegistry.values().length, distinct);
    }
}
