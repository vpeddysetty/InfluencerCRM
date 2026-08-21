package com.influencer.webe.security;

import com.influencer.webe.config.WebExperienceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same site is served from more than one hostname, so CORS has to allow more than one origin.
 *
 * <p>Only the apex was allowed, so a visitor who typed www loaded the page, filled the sign-up form,
 * and had the request blocked before it left the browser — surfaced in the UI as "Failed to fetch",
 * which reads like a network fault rather than a configuration one.
 *
 * <p>No wildcard is available as a shortcut: credentials are allowed, and the CORS spec forbids
 * {@code Access-Control-Allow-Origin: *} alongside them. Enumeration is the only correct form.
 */
class CorsOriginsTest {

    private WebExperienceProperties withUiBaseUrl(String value) {
        WebExperienceProperties properties = new WebExperienceProperties();
        properties.setUiBaseUrl(value);
        return properties;
    }

    @Test
    @DisplayName("a link points at ONE origin, never the whole list")
    void primaryOriginIsTheFirstEntry() {
        // A share link reading "https://tejdux.com,https://www.tejdux.com/share/abc" is not a link.
        assertEquals("https://tejdux.com",
                withUiBaseUrl("https://tejdux.com,https://www.tejdux.com").getPrimaryUiBaseUrl());
    }

    @Test
    @DisplayName("a single configured origin still works unchanged")
    void singleOriginIsUnaffected() {
        assertEquals("https://tejdux.com", withUiBaseUrl("https://tejdux.com").getPrimaryUiBaseUrl());
    }

    @Test
    @DisplayName("whitespace around a hand-edited list is tolerated")
    void whitespaceIsTrimmed() {
        assertEquals("https://tejdux.com",
                withUiBaseUrl("  https://tejdux.com , https://www.tejdux.com ").getPrimaryUiBaseUrl());
    }

    @Test
    @DisplayName("an unset value is passed through rather than turned into an empty string")
    void unsetStaysUnset() {
        assertEquals(null, withUiBaseUrl(null).getPrimaryUiBaseUrl());
    }
}
