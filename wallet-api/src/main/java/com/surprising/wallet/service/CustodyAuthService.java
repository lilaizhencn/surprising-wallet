package com.surprising.wallet.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.surprising.wallet.custody.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.repository.CustodyRepository.AuthUser;
import com.surprising.wallet.repository.CustodyRepository.SessionRecord;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.repository.CustodyRepository;
import com.surprising.wallet.custody.model.CustodySecurityProperties;
import com.surprising.wallet.custody.exception.CustodyUnauthorizedException;

/**
 * 托管认证服务，管理 API Key 验证和 Console 会话。
 *
 * <p>支持两种认证模式：
 * <ul>
 *   <li>API 认证：通过 API Key + HMAC 签名验证</li>
 *   <li>Console 认证：Email + 密码登录，Session Cookie 管理</li>
 * </ul>
 *
 * <p>应用启动时自动预创建默认平台管理员账号。
 */
@Service
public class CustodyAuthService {
    /**
     * 定义 {@code MAX_FAILURES} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int MAX_FAILURES = 5;
    /**
     * 定义 {@code LOCK_DURATION} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final CustodyRepository repository;
    /**
     * 保存 {@code passwords}，用于保存签名、认证或密钥相关材料。
     */
    private final CustodyPasswordService passwords;
    /**
     * 保存 {@code crypto}，用于承载当前对象的运行配置或业务数据。
     */
    private final CustodyCryptoService crypto;
    /**
     * 保存 {@code properties}，用于承载当前对象的运行配置或业务数据。
     */
    private final CustodySecurityProperties properties;

    /**
     * 构造 {@code CustodyAuthService}，初始化该组件运行所需的状态和依赖。
     */
    public CustodyAuthService(CustodyRepository repository, CustodyPasswordService passwords,
                              CustodyCryptoService crypto, CustodySecurityProperties properties) {
        this.repository = repository;
        this.passwords = passwords;
        this.crypto = crypto;
        this.properties = properties;
    }

    /**
     * 执行 {@code tenantLogin} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(
            rollbackFor = Throwable.class,
            noRollbackFor = {CustodyUnauthorizedException.class, CustodyForbiddenException.class})
    public LoginResult tenantLogin(String email, String password,
                                   String sourceIp, String userAgent) {
        AuthUser user = repository.findTenantUser(normalizeEmail(email))
                .orElseThrow(() -> new CustodyUnauthorizedException("invalid credentials"));
        return authenticate(user, password, sourceIp, userAgent, ActorType.TENANT_USER);
    }

    /**
     * 执行 {@code platformLogin} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(
            rollbackFor = Throwable.class,
            noRollbackFor = {CustodyUnauthorizedException.class, CustodyForbiddenException.class})    public LoginResult platformLogin(String email, String password, String sourceIp, String userAgent) {
        AuthUser user = repository.findPlatformUser(normalizeEmail(email))
                .orElseThrow(() -> new CustodyUnauthorizedException("invalid credentials"));
        return authenticate(user, password, sourceIp, userAgent, ActorType.PLATFORM_USER);
    }
    /**
     * 校验 {@code requireSession} 对应的前置条件，不满足时抛出明确异常。
     */
    public CustodyPrincipal requireSession(String token, boolean platformRoute) {
        requireSessionToken(token);
        String tokenHash = crypto.sha256(token);
        SessionRecord session = repository.findActiveSession(tokenHash)
                .orElseThrow(() -> new CustodyUnauthorizedException("session expired or invalid"));
        if (!"ACTIVE".equals(session.userStatus()) || !"ACTIVE".equals(session.tenantStatus())) {
            throw new CustodyForbiddenException("account or tenant is not active");
        }
        boolean platformUser = "PLATFORM_ADMIN".equals(session.role()) && session.tenantId() == null;
        if (platformRoute != platformUser) {
            throw new CustodyForbiddenException("route is outside the authenticated account scope");
        }
        repository.touchSession(session.sessionId());
        return new CustodyPrincipal(
                platformUser ? ActorType.PLATFORM_USER : ActorType.TENANT_USER,
                session.userId(),
                session.tenantId(),
                session.tenantSlug(),
                session.role(),
                consoleScopes(session.role()));
    }
    /**
     * 执行 {@code logout} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public void logout(String token) {
        requireSessionToken(token);
        repository.revokeSession(crypto.sha256(token));
    }
    /**
     * 执行 {@code sessionCookieSecure} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean sessionCookieSecure() {
        return properties.isSessionCookieSecure();
    }

    /**
     * 校验 {@code requireSessionToken} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireSessionToken(String token) {
        if (token == null || !token.startsWith("cs_") || token.length() < 32) {
            throw new CustodyUnauthorizedException("session token required");
        }
    }

    /**
     * 执行 {@code bootstrapPlatformAdmin} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapPlatformAdmin() {
        String email = properties.getPlatformAdmin().getEmail();
        String password = properties.getPlatformAdmin().getPassword();
        if (email.isBlank() || password.isBlank() || repository.platformAdminExists()) {
            return;
        }
        repository.insertPlatformAdmin(UUID.randomUUID(), normalizeEmail(email), passwords.hash(password));
    }

    /**
     * 执行 {@code authenticate} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private LoginResult authenticate(AuthUser user, String password, String sourceIp, String userAgent,
                                     ActorType actorType) {
        Instant now = Instant.now();
        if (!"ACTIVE".equals(user.status()) || !"ACTIVE".equals(user.tenantStatus())) {
            throw new CustodyForbiddenException("account or tenant is not active");
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            throw new CustodyUnauthorizedException("account temporarily locked");
        }
        if (!passwords.verify(password, user.passwordHash())) {
            Instant lockedUntil = user.failedLoginCount() + 1 >= MAX_FAILURES
                    ? now.plus(LOCK_DURATION)
                    : null;
            repository.recordLoginFailure(user.id(), lockedUntil);
            throw new CustodyUnauthorizedException("invalid credentials");
        }
        repository.recordLoginSuccess(user.id());
        String rawToken = "cs_" + crypto.randomSecret(32);
        Instant expiresAt = now.plus(validatedSessionTtl());
        repository.insertSession(UUID.randomUUID(), user.id(), user.tenantId(), crypto.sha256(rawToken),
                sourceIp, userAgent, expiresAt);
        repository.audit(user.tenantId(), actorType.name(), user.id().toString(), "AUTH.LOGIN",
                "SESSION", null, sourceIp, "{}");
        return new LoginResult(
                rawToken,
                expiresAt,
                user.id(),
                user.tenantId(),
                user.tenantSlug(),
                user.email(),
                user.displayName(),
                user.role(),
                consoleScopes(user.role()));
    }
    /**
     * 校验 {@code validatedSessionTtl} 对应的前置条件，不满足时抛出明确异常。
     */
    private Duration validatedSessionTtl() {
        Duration ttl = properties.getSessionTtl();
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalStateException("custody session TTL must be between 1 second and 7 days");
        }
        return ttl;
    }
    /**
     * 执行 {@code consoleScopes} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static Set<String> consoleScopes(String role) {
        return switch (role) {
            case "PLATFORM_ADMIN", "TENANT_ADMIN" -> Set.of("*");
            case "OPERATOR" -> Set.of(
                    "addresses:read", "addresses:write", "assets:read", "deposits:read",
                    "withdrawals:read", "withdrawals:write", "webhooks:read", "chains:read");
            default -> Set.of(
                    "addresses:read", "assets:read", "deposits:read",
                    "withdrawals:read", "webhooks:read", "audit:read", "chains:read");
        };
    }
    /**
     * 转换或计算 {@code normalizeEmail} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.contains("@")) {
            throw new CustodyUnauthorizedException("invalid credentials");
        }
        return value;
    }

    public record LoginResult(
            @JsonIgnore
            String token,
            Instant expiresAt,
            UUID userId,
            UUID tenantId,
            String tenantSlug,
            String email,
            String displayName,
            String role,
            Set<String> scopes
    ) {
    }
}
