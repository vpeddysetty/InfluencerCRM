package com.influencer.dps.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "dps")
public class DpsProperties {

    /** The BFF the DPS brokers authentication and API calls through. */
    private String bffBaseUrl = "http://localhost:8081";

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

    public String getBffBaseUrl() {
        return bffBaseUrl;
    }

    public void setBffBaseUrl(String bffBaseUrl) {
        this.bffBaseUrl = bffBaseUrl;
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
}
