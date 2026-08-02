package com.influencer.webe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "web-experience")
public class WebExperienceProperties {
    private String daoBaseUrl;
    private String agentBaseUrl;
    private String uiBaseUrl;
    private long sessionTtlMinutes = 720;
    private long accessTokenTtlMinutes = 30;
    private long refreshTokenTtlMinutes = 43200;
    private String jwtSigningKey;
    private String daoServiceToken;
    private boolean daoTlsVerificationEnabled = true;
    private String daoTrustStore;
    private String daoTrustStorePassword;
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
