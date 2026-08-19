package com.influencer.dps.security;

import com.influencer.dps.config.DpsProperties;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;

import jakarta.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the scope of the CSRF cookie, and that a real servlet container will accept it.
 *
 * <p>The SPA runs on {@code www.tejdux.com} and the DPS answers on {@code api.tejdux.com}. Spring
 * writes the CSRF cookie host-only by default, so it landed on the API host while the app read
 * {@code document.cookie} on the web host and found nothing. The header could never be echoed, and
 * every state-changing call through {@code /dps/api/**} came back 403 with no body — a handle
 * lookup, adding a brand and renaming a workspace all failed identically.
 *
 * <p><b>Why this test calls the production code instead of mirroring it.</b> The sibling
 * {@code SessionCookieDomainTest} reproduces its builder inline, which is why nothing caught the
 * first attempt at this fix: it set {@code Domain=.tejdux.com}, taken straight from
 * {@code dps.cookie-domain}. Spring's {@code ResponseCookie} accepts a leading dot, but this cookie
 * is written by Tomcat, whose RFC 6265 processor rejects it — {@code IllegalArgumentException: An
 * invalid domain [.tejdux.com] was specified for this cookie}. Thrown inside the CSRF filter, that
 * 500s every request and takes the whole API down, which is exactly what it did in production. So
 * the assertions below run the configured value through {@link DpsSecurityConfig} and then hand the
 * result to the same validator Tomcat uses.
 */
class CsrfCookieDomainTest {

    private static Cookie writeToken(DpsProperties properties) {
        CookieCsrfTokenRepository repository = new DpsSecurityConfig(properties).csrfTokenRepository();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);
        return response.getCookie(token.getParameterName() == null ? "XSRF-TOKEN" : "XSRF-TOKEN");
    }

    @Test
    @DisplayName("the configured leading dot is stripped, because Tomcat rejects it")
    void stripsLeadingDot() {
        DpsProperties properties = new DpsProperties();
        properties.setCookieDomain(".tejdux.com");

        Cookie cookie = writeToken(properties);

        assertNotNull(cookie, "a CSRF cookie must be written");
        assertEquals("tejdux.com", cookie.getDomain(),
                "the leading dot must be removed; RFC 6265 makes it redundant and Tomcat rejects it");
    }

    @Test
    @DisplayName("the domain Tomcat is handed actually validates")
    void tomcatAcceptsTheDomain() {
        DpsProperties properties = new DpsProperties();
        properties.setCookieDomain(".tejdux.com");

        Cookie cookie = writeToken(properties);
        Rfc6265CookieProcessor processor = new Rfc6265CookieProcessor();

        // The exact call that threw in production, inside the CSRF filter, on every request.
        assertDoesNotThrow(() -> processor.generateHeader(cookie, null),
                "Tomcat must accept the domain, or every request through the DPS 500s");
    }

    @Test
    @DisplayName("the scope still covers both the app host and the API host")
    void scopeStillSpansSubdomains() {
        DpsProperties properties = new DpsProperties();
        properties.setCookieDomain(".tejdux.com");

        // Domain=tejdux.com matches the host AND every subdomain under RFC 6265, so dropping the
        // dot does not narrow anything — www and api still share the cookie, which is the point.
        assertEquals("tejdux.com", writeToken(properties).getDomain());
    }

    @Test
    @DisplayName("no configured domain leaves the cookie host-only")
    void noDomainConfiguredStaysHostOnly() {
        // Local development is all localhost, where a Domain on a bare hostname is rejected and the
        // browser drops the cookie entirely. Host-only is correct there.
        DpsProperties properties = new DpsProperties();
        properties.setCookieDomain("");

        assertNull(writeToken(properties).getDomain(), "an unset domain must stay host-only");
    }

    @Test
    @DisplayName("a domain of only dots is treated as unset rather than as an empty domain")
    void dotsOnlyIsTreatedAsUnset() {
        DpsProperties properties = new DpsProperties();
        properties.setCookieDomain(".");

        assertNull(writeToken(properties).getDomain(),
                "an empty Domain attribute is invalid and makes a browser drop the cookie");
    }
}
