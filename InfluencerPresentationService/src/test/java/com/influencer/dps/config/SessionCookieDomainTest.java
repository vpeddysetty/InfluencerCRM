package com.influencer.dps.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the scope of the session cookie, which decides whether a completed sign-in is visible.
 *
 * <p>The DPS answers on one hostname and the UI is served from another — {@code api.tejdux.com} and
 * {@code tejdux.com}. A cookie with no {@code Domain} attribute is host-only, so one set while
 * answering on the API host never reaches the UI host. The failure mode is quiet and easy to
 * misread: OAuth completes, the session exists server-side, the browser is redirected to the UI, and
 * the SPA's first {@code /dps/session} call arrives without the cookie — so the app renders the
 * signed-out landing page as though the sign-in had failed. Nothing errors anywhere.
 *
 * <p>The domain therefore has to be the shared parent, and it must be configurable rather than
 * fixed: local development is all {@code localhost}, where a {@code Domain} on a bare hostname is
 * rejected and the browser drops the cookie entirely.
 */
class SessionCookieDomainTest {

    /** Mirrors {@code SessionController#cookieBuilder}. */
    private ResponseCookie build(DpsProperties properties) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.getCookieName(), "v")
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path("/")
                .maxAge(Duration.ofMinutes(30));
        String domain = properties.getCookieDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain.trim());
        }
        return builder.build();
    }

    @Test
    @DisplayName("a configured domain is applied, so the cookie reaches the UI host")
    void configuredDomainIsApplied() {
        DpsProperties properties = new DpsProperties();
        properties.setCookieDomain(".tejdux.com");

        assertEquals(".tejdux.com", build(properties).getDomain());
    }

    @Test
    @DisplayName("a blank domain leaves the cookie host-only rather than emitting an empty attribute")
    void blankDomainIsOmittedEntirely() {
        // The distinction matters: `Domain=` is invalid, and a browser drops the whole cookie rather
        // than falling back to host-only. That would turn "not configured" into "no session at all"
        // for every local run.
        DpsProperties properties = new DpsProperties();
        properties.setCookieDomain("   ");

        ResponseCookie cookie = build(properties);
        assertEquals(null, cookie.getDomain());
        assertFalse(cookie.toString().contains("Domain="), "no empty Domain attribute may be emitted");
    }

    @Test
    @DisplayName("the default is host-only, which is what localhost needs")
    void defaultsToHostOnly() {
        assertEquals("", new DpsProperties().getCookieDomain());
    }

    @Test
    @DisplayName("the deployment scopes the cookie to the apex, not to the API host")
    void deploymentUsesTheSharedParentDomain() throws IOException {
        Path tf = Path.of("..", "infrastructure", "test", "terraform", "compose-ec2.tf");
        if (!Files.exists(tf)) {
            // Built standalone, without the infrastructure tree checked out.
            return;
        }

        String text = Files.readString(tf, StandardCharsets.UTF_8);
        assertTrue(
                text.contains("cookie_domain = var.api_domain != \"\" ? \".${var.root_domain}\" : \"\""),
                "the session cookie must be scoped to the apex domain when deployed; scoped to the "
                        + "API host it never reaches the UI, and a completed sign-in renders as a "
                        + "signed-out landing page");
    }
}
