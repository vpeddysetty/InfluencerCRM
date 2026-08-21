package com.influencer.dps.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "dps")
public class DpsProperties {

    /**
     * The BFF the DPS brokers authentication and API calls through, SERVER TO SERVER.
     *
     * <p>In the deployed stack this is a container-network name — {@code http://web-experience:8081}
     * — which resolves inside the compose bridge and nowhere else. Correct for calls the DPS makes
     * itself; never put it in a {@code Location} header. See {@link #getPublicBffBaseUrl()}.
     */
    private String bffBaseUrl = "http://localhost:8081";

    /**
     * The BFF address a BROWSER can reach, for redirects.
     *
     * <p>Separate from {@link #bffBaseUrl} because the two have different audiences and, in the
     * deployed stack, different values. Using the internal one for a redirect is what took Google
     * sign-in down: the browser was sent to {@code http://web-experience:8081}, a name it cannot
     * resolve, and the sign-in silently never started.
     *
     * <p>Defaults to empty rather than to {@code bffBaseUrl}. A default that "works" in development
     * and points at an unreachable host in production is precisely the failure being fixed, so this
     * fails loudly instead — see {@link #requirePublicBffBaseUrl()}.
     */
    private String publicBffBaseUrl = "";

    /** Credential presented to the BFF, so it can distinguish the DPS from arbitrary traffic. */
    private String serviceToken;

    /**
     * Origins allowed to call the DPS with credentials.
     *
     * <p>Every micro-frontend origin must be listed. A wildcard is not an option here: cookies are
     * only sent cross-origin when {@code Access-Control-Allow-Credentials} is true, and the spec
     * forbids pairing that with {@code *} — so an explicit allowlist is required, not merely
     * advisable.
     */
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:5173",
            "http://localhost:5174",
            "http://localhost:5175",
            "http://localhost:5176",
            "http://localhost:5177",
            "http://localhost:5178",
            "http://localhost:5179"));

    /**
     * Origin of the React shell, where the OAuth flow returns the user after signing in.
     *
     * <p>Only used for redirects the DPS itself issues. The session cookie is set on that redirect,
     * so the SPA is authenticated before its first request.
     */
    private String uiBaseUrl = "http://localhost:5173";

    /** Sliding session lifetime. Activity extends it; idleness ends it. */
    private long sessionTtlMinutes = 480;

    /** Backstop against unbounded memory growth in the in-memory store. */
    private long maxSessions = 50_000;

    private String cookieName = "INFLUENCRM_SESSION";

    /**
     * Whether the session cookie is marked {@code Secure}.
     *
     * <p>Off for local HTTP development only. A Secure cookie is never sent over plain HTTP, so
     * leaving this on locally would silently break every login.
     */
    private boolean cookieSecure = false;

    /**
     * {@code SameSite} for the session cookie.
     *
     * <p>{@code Lax} suits a single-origin app. Micro-frontends on separate origins need
     * {@code None} — with {@code Secure}, which the spec requires — for the cookie to travel at all.
     */
    private String cookieSameSite = "Lax";

    /**
     * Domain the session cookie is scoped to. Blank leaves it host-only.
     *
     * <p><b>Why this has to be settable.</b> The DPS is reached at one hostname and the UI is served
     * from another — {@code api.tejdux.com} and {@code tejdux.com} in production. A cookie with no
     * {@code Domain} attribute is host-only, so one set while answering on {@code api.tejdux.com} is
     * never sent to {@code tejdux.com}. Setting it to the shared parent, {@code .tejdux.com}, makes
     * it travel to both.
     *
     * <p>That gap is what made a successful social sign-in look like a failed one: the OAuth flow
     * completed, the session existed server-side, the browser was redirected to the UI — and the
     * SPA's first {@code /dps/session} call arrived without the cookie, so the app rendered the
     * signed-out landing page. Nothing in the flow errored; the session simply could not be seen.
     *
     * <p>Blank by default, because host-only is right for local development, where everything is
     * {@code localhost} and a {@code Domain} attribute on a bare hostname is rejected outright.
     *
     * <p>Widen this no further than necessary: the cookie is sent to every subdomain of whatever is
     * set here, so a parent domain shared with hosts that should not receive the session is not a
     * valid value.
     */
    private String cookieDomain = "";

    public String getBffBaseUrl() {
        return bffBaseUrl;
    }

    public void setBffBaseUrl(String bffBaseUrl) {
        this.bffBaseUrl = bffBaseUrl;
    }

    public String getPublicBffBaseUrl() {
        return publicBffBaseUrl;
    }

    public void setPublicBffBaseUrl(String publicBffBaseUrl) {
        this.publicBffBaseUrl = publicBffBaseUrl;
    }

    /**
     * The browser-facing BFF URL, or a clear failure.
     *
     * <p>Unset, this throws rather than falling back to {@link #getBffBaseUrl()}. The fallback is
     * the tempting option and it is the bug: in development both values are localhost so it looks
     * fine, and in production it emits a redirect to a container hostname that no browser can
     * resolve. A 500 naming the missing property is far easier to diagnose than a sign-in button
     * that spins forever.
     */
    public String requirePublicBffBaseUrl() {
        if (publicBffBaseUrl == null || publicBffBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "dps.public-bff-base-url is not set. It is the BFF address a BROWSER can reach "
                            + "and is required for OAuth redirects; dps.bff-base-url is the internal "
                            + "server-to-server address and must not be used for them.");
        }
        return publicBffBaseUrl;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String getUiBaseUrl() {
        return uiBaseUrl;
    }

    public void setUiBaseUrl(String uiBaseUrl) {
        this.uiBaseUrl = uiBaseUrl;
    }

    public long getSessionTtlMinutes() {
        return sessionTtlMinutes;
    }

    public void setSessionTtlMinutes(long sessionTtlMinutes) {
        this.sessionTtlMinutes = sessionTtlMinutes;
    }

    public long getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(long maxSessions) {
        this.maxSessions = maxSessions;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public void setCookieSameSite(String cookieSameSite) {
        this.cookieSameSite = cookieSameSite;
    }

    public String getCookieDomain() {
        return cookieDomain;
    }

    public void setCookieDomain(String cookieDomain) {
        this.cookieDomain = cookieDomain;
    }
}
