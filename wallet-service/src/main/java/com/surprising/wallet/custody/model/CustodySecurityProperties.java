package com.surprising.wallet.custody.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "sw.wallet.custody")
public class CustodySecurityProperties {
    private String secretMasterKey = "";
    private Duration sessionTtl = Duration.ofHours(12);
    private Duration apiClockSkew = Duration.ofMinutes(5);
    private boolean sessionCookieSecure;
    private final PlatformAdmin platformAdmin = new PlatformAdmin();
    public String getSecretMasterKey() {
        return secretMasterKey;
    }
    public void setSecretMasterKey(String secretMasterKey) {
        this.secretMasterKey = secretMasterKey == null ? "" : secretMasterKey.trim();
    }
    public Duration getSessionTtl() {
        return sessionTtl;
    }
    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl == null ? Duration.ofHours(12) : sessionTtl;
    }
    public Duration getApiClockSkew() {
        return apiClockSkew;
    }
    public void setApiClockSkew(Duration apiClockSkew) {
        this.apiClockSkew = apiClockSkew == null ? Duration.ofMinutes(5) : apiClockSkew;
    }
    public PlatformAdmin getPlatformAdmin() {
        return platformAdmin;
    }
    public boolean isSessionCookieSecure() {
        return sessionCookieSecure;
    }
    public void setSessionCookieSecure(boolean sessionCookieSecure) {
        this.sessionCookieSecure = sessionCookieSecure;
    }
    public static class PlatformAdmin {
        private String email = "";
        private String password = "";

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email == null ? "" : email.trim();
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }
    }
}
