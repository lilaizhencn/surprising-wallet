package com.surprising.wallet.custody.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 托管安全配置属性，从 {@code sw.wallet.custody} 前缀绑定环境变量。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code secretMasterKey} — 32 字节 AES-256 主密钥（Base64 或十六进制），用于 API Key Secret 加密和 Webhook HMAC 签名</li>
 *   <li>{@code sessionTtl} — Console 会话过期时间，默认 12 小时</li>
 *   <li>{@code apiClockSkew} — API 签名时间戳允许的时钟偏差，默认 5 分钟</li>
 *   <li>{@code sessionCookieSecure} — Session Cookie 是否设置 Secure 标志</li>
 *   <li>{@code platformAdmin} — 平台初始管理员邮箱和密码</li>
 * </ul>
 *
 * @see com.surprising.wallet.custody.service.CustodyCryptoService
 */
@Component
@ConfigurationProperties(prefix = "sw.wallet.custody")
public class CustodySecurityProperties {

    /** AES-256 主密钥，用于 API Key Secret 加密和 Webhook HMAC 签名 */
    private String secretMasterKey = "";
    /** Console 会话过期时间，默认 12 小时 */
    private Duration sessionTtl = Duration.ofHours(12);
    /** API 签名时间戳允许的时钟偏差，默认 5 分钟 */
    private Duration apiClockSkew = Duration.ofMinutes(5);
    /** Session Cookie 是否设置 Secure 标志 */
    private boolean sessionCookieSecure;
    /** 平台初始管理员账号配置 */
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
    /** 平台初始管理员账号 */
    public static class PlatformAdmin {
        /** 管理员邮箱 */
        private String email = "";
        /** 管理员密码（明文，应用启动后应立即修改） */
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
