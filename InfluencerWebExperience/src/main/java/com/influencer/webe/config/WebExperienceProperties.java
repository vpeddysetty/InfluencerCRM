package com.influencer.webe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "web-experience")
public class WebExperienceProperties {
    private String daoBaseUrl;
    private String agentBaseUrl;
    private String uiBaseUrl;

    /**
     * Origin of the Digital Presentation Service, where the OAuth callback hands off.
     *
     * <p>The completed sign-in is delivered here rather than to the UI: the DPS turns it into an
     * httpOnly cookie session, so no token reaches JavaScript. Bouncing it to a browser page
     * instead — as the earlier fragment-based flow did — puts the tokens somewhere script can read.
     */
    private String dpsBaseUrl = "http://localhost:8090";

    /** This service's own externally-reachable origin, used to build provider redirect URIs. */
    private String publicBaseUrl = "http://localhost:8081";
    private long sessionTtlMinutes = 720;
    private long accessTokenTtlMinutes = 30;
    private long refreshTokenTtlMinutes = 43200;
    private String jwtSigningKey;
    // Escape hatch for a single-process local run. Anywhere with more than one instance — which now
    // includes any deployment, since Workflow is its own service — must configure a real key.
    private boolean allowEphemeralJwtKey = false;
    /**
     * Public JWKs of rotated-out keys, comma-separated. Still trusted for verification so a
     * rotation does not invalidate tokens already in flight.
     */
    private String jwtPreviousKeys;
    private String daoServiceToken;
    private boolean daoTlsVerificationEnabled = true;
    private String daoTrustStore;
    private String daoTrustStorePassword;

    // --- Workflow service extraction (first extracted context) ---
    // The flag is what makes the cutover reversible in seconds rather than a redeploy.
    private boolean workflowServiceEnabled = false;
    private String workflowServiceBaseUrl = "http://localhost:8444";
    private String workflowServiceToken;
    private final Provider oauth = new Provider();

    public String getDaoBaseUrl() {
        return daoBaseUrl;
    }

    public void setDaoBaseUrl(String daoBaseUrl) {
        this.daoBaseUrl = daoBaseUrl;
    }

    public String getUiBaseUrl() {
        return uiBaseUrl;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getDpsBaseUrl() {
        return dpsBaseUrl;
    }

    public void setDpsBaseUrl(String dpsBaseUrl) {
        this.dpsBaseUrl = dpsBaseUrl;
    }

    public void setUiBaseUrl(String uiBaseUrl) {
        this.uiBaseUrl = uiBaseUrl;
    }

    public String getAgentBaseUrl() {
        return agentBaseUrl;
    }

    public void setAgentBaseUrl(String agentBaseUrl) {
        this.agentBaseUrl = agentBaseUrl;
    }

    public long getSessionTtlMinutes() {
        return sessionTtlMinutes;
    }

    public void setSessionTtlMinutes(long sessionTtlMinutes) {
        this.sessionTtlMinutes = sessionTtlMinutes;
    }

    public long getAccessTokenTtlMinutes() {
        return accessTokenTtlMinutes;
    }

    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    public long getRefreshTokenTtlMinutes() {
        return refreshTokenTtlMinutes;
    }

    public void setRefreshTokenTtlMinutes(long refreshTokenTtlMinutes) {
        this.refreshTokenTtlMinutes = refreshTokenTtlMinutes;
    }

    /** RSA JWK (JSON, including the private key) used to sign access tokens. */
    public String getJwtSigningKey() {
        return jwtSigningKey;
    }

    public void setJwtSigningKey(String jwtSigningKey) {
        this.jwtSigningKey = jwtSigningKey;
    }

    public String getJwtPreviousKeys() {
        return jwtPreviousKeys;
    }

    public void setJwtPreviousKeys(String jwtPreviousKeys) {
        this.jwtPreviousKeys = jwtPreviousKeys;
    }

    public boolean isAllowEphemeralJwtKey() {
        return allowEphemeralJwtKey;
    }

    public void setAllowEphemeralJwtKey(boolean allowEphemeralJwtKey) {
        this.allowEphemeralJwtKey = allowEphemeralJwtKey;
    }

    /** Shared secret presented to the DAO so it can reject non-service traffic. */
    public String getDaoServiceToken() {
        return daoServiceToken;
    }

    public void setDaoServiceToken(String daoServiceToken) {
        this.daoServiceToken = daoServiceToken;
    }

    public boolean isDaoTlsVerificationEnabled() {
        return daoTlsVerificationEnabled;
    }

    public void setDaoTlsVerificationEnabled(boolean daoTlsVerificationEnabled) {
        this.daoTlsVerificationEnabled = daoTlsVerificationEnabled;
    }

    /** Path to a truststore containing the DAO's certificate (supports the local self-signed cert). */
    public String getDaoTrustStore() {
        return daoTrustStore;
    }

    public void setDaoTrustStore(String daoTrustStore) {
        this.daoTrustStore = daoTrustStore;
    }

    public String getDaoTrustStorePassword() {
        return daoTrustStorePassword;
    }

    public void setDaoTrustStorePassword(String daoTrustStorePassword) {
        this.daoTrustStorePassword = daoTrustStorePassword;
    }

    /** Routes Workflow endpoints to the extracted service instead of the monolith DAO. */
    public boolean isWorkflowServiceEnabled() {
        return workflowServiceEnabled;
    }

    public void setWorkflowServiceEnabled(boolean workflowServiceEnabled) {
        this.workflowServiceEnabled = workflowServiceEnabled;
    }

    public String getWorkflowServiceBaseUrl() {
        return workflowServiceBaseUrl;
    }

    public void setWorkflowServiceBaseUrl(String workflowServiceBaseUrl) {
        this.workflowServiceBaseUrl = workflowServiceBaseUrl;
    }

    public String getWorkflowServiceToken() {
        return workflowServiceToken;
    }

    public void setWorkflowServiceToken(String workflowServiceToken) {
        this.workflowServiceToken = workflowServiceToken;
    }

    public Provider getOauth() {
        return oauth;
    }

    public static class Provider {
        private final Google google = new Google();
        private final Facebook facebook = new Facebook();

        public Google getGoogle() {
            return google;
        }

        public Facebook getFacebook() {
            return facebook;
        }
    }

    public static class Google {
        private String authorizationUri;
        private String tokenUri;
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private String userinfoUri;

        public String getAuthorizationUri() {
            return authorizationUri;
        }

        public void setAuthorizationUri(String authorizationUri) {
            this.authorizationUri = authorizationUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String getUserinfoUri() {
            return userinfoUri;
        }

        public void setUserinfoUri(String userinfoUri) {
            this.userinfoUri = userinfoUri;
        }
    }

    public static class Facebook {
        private String authorizationUri;
        private String tokenUri;
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private String userinfoUri;

        /**
         * Scopes requested at the consent screen.
         *
         * <p>Configurable, and defaulted to include {@code pages_show_list}, because what is valid
         * here depends on the app's TYPE in the Meta dashboard rather than on anything in this code.
         * A Business-type app uses <em>Facebook Login for Business</em>, which requires at least one
         * business permission alongside {@code email} and {@code public_profile}; asking for only
         * those two is refused outright with {@code Invalid Scopes: email}. A Consumer-type app is
         * the opposite: it has no business permissions and wants exactly those two.
         *
         * <p>TejDux is Business-type — that is what the Instagram Graph API needs, so it is not a
         * setting to undo. {@code pages_show_list} is the cheapest permission that satisfies the
         * rule: it needs no App Review at Standard Access, and it is already on the submission list
         * in docs/platform-app-registration.md because linking a Facebook Page is how an Instagram
         * Business account is reached at all.
         *
         * <p>Being a property rather than a literal means a scope rejection is a config change and a
         * restart, not a rebuild and a redeploy — which matters while the dashboard side is still
         * being settled.
         */
        private String scope = "email,public_profile,pages_show_list";

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getAuthorizationUri() {
            return authorizationUri;
        }

        public void setAuthorizationUri(String authorizationUri) {
            this.authorizationUri = authorizationUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String getUserinfoUri() {
            return userinfoUri;
        }

        public void setUserinfoUri(String userinfoUri) {
            this.userinfoUri = userinfoUri;
        }
    }
}
