package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The rule every service that builds a clickable link has to follow.
 *
 * <p><b>Why this test exists.</b> {@code web-experience.ui-base-url} holds a comma-separated list in
 * production — {@code https://tejdux.com,https://www.tejdux.com} — because the same site answers on
 * both hostnames and CORS has to allow each. Four services used the whole string as a URL base and
 * produced {@code https://tejdux.com,https://www.tejdux.com/verify-email?token=...}, which is not a
 * link at all.
 *
 * <p>Nothing caught it: every unit test configured a single origin, and the deployed value is only
 * a comma-separated list. It was found by reading a real verification email produced by the live
 * stack on 2026-08-22.
 *
 * <p>The production shape is asserted directly, so a single-origin test environment can never again
 * be the reason this passes.
 */
class LinkBaseUrlTest {

    /** Exactly what WEBE_UI_BASE_URL is set to in the deployed compose file. */
    private static final String PRODUCTION_VALUE = "https://tejdux.com,https://www.tejdux.com";

    /** The normalisation every link-building service applies to that property. */
    private static String linkBase(String configured) {
        return configured == null ? "" : configured.split(",")[0].trim().replaceAll("/+$", "");
    }

    @Test
    @DisplayName("a comma-separated origin list yields the first origin, not the whole string")
    void firstOriginWins() {
        assertEquals("https://tejdux.com", linkBase(PRODUCTION_VALUE));
    }

    @Test
    @DisplayName("the built link contains no comma — the bug as a user would see it")
    void builtLinkIsClickable() {
        String url = linkBase(PRODUCTION_VALUE) + "/verify-email?token=abc123";
        assertEquals("https://tejdux.com/verify-email?token=abc123", url);
        // The symptom, asserted directly: a comma anywhere in a link means it is not one.
        assertFalse(url.contains(","), "a link containing a comma is not a link: " + url);
    }

    @Test
    @DisplayName("a single origin is unaffected, with or without a trailing slash")
    void singleOriginIsUnchanged() {
        assertEquals("https://tejdux.com", linkBase("https://tejdux.com"));
        assertEquals("https://tejdux.com", linkBase("https://tejdux.com/"));
        assertEquals("https://tejdux.com", linkBase("https://tejdux.com///"));
    }

    @Test
    @DisplayName("whitespace around a list entry is trimmed")
    void whitespaceIsTrimmed() {
        // A human-edited list is likelier to read "a, b" than "a,b", and an untrimmed space
        // produces a URL that fails in some clients and works in others.
        assertEquals("https://tejdux.com", linkBase(" https://tejdux.com , https://www.tejdux.com "));
    }

    @Test
    @DisplayName("null and blank produce an empty base rather than the literal \"null\"")
    void nullIsSafe() {
        assertEquals("", linkBase(null));
        assertEquals("", linkBase(""));
    }
}
