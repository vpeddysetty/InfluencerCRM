package com.influencer.webe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "web-experience")
public class WebExperienceProperties {
    private String daoBaseUrl;
    private long sessionTtlMinutes = 720;
    private final Provider oauth = new Provider();

    public String getDaoBaseUrl() {
        return daoBaseUrl;
    }

    public void setDaoBaseUrl(String daoBaseUrl) {
        this.daoBaseUrl = daoBaseUrl;
    }

    public long getSessionTtlMinutes() {
        return sessionTtlMinutes;
    }

    public void setSessionTtlMinutes(long sessionTtlMinutes) {
        this.sessionTtlMinutes = sessionTtlMinutes;
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
