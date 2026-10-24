package com.surprising.wallet.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.exception.CustodyUnauthorizedException;
import com.surprising.wallet.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.custody.AuthUser;
import com.surprising.wallet.model.custody.TenantRecord;
import com.surprising.wallet.model.custody.TenantUserRecord;
import com.surprising.wallet.model.custody.SessionRecord;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodySessionRepository;
import com.surprising.wallet.repository.CustodyTenantUserRepository;
import com.surprising.wallet.repository.CustodyTenantTableRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Console 登录、会话校验及平台管理员初始化。 */
@Service
@RequiredArgsConstructor
public class CustodyAuthService {
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private final CustodyTenantTableRepository tenants;
    private final CustodyTenantUserRepository users;
    private final CustodySessionRepository sessions;
    private final CustodyAuditRepository audits;
    private final CustodyPasswordService passwords;
    private final CustodyCryptoService crypto;
    private final CustodySecurityProperties properties;

    // 密码错误需要提交失败计数；会话、审计等写入异常仍回滚整次登录。
    @Transactional(
            rollbackFor = Throwable.class,
            noRollbackFor = {CustodyUnauthorizedException.class, CustodyForbiddenException.class})
    public LoginResult tenantLogin(String email, String password,
                                   String sourceIp, String userAgent) {
        AuthUser user = findTenantUser(normalizeEmail(email))
                .orElseThrow(() -> new CustodyUnauthorizedException("invalid credentials"));
        return authenticate(user, password, sourceIp, userAgent, ActorType.TENANT_USER);
    }

    @Transactional(
            rollbackFor = Throwable.class,
            noRollbackFor = {CustodyUnauthorizedException.class, CustodyForbiddenException.class})
    public LoginResult platformLogin(String email, String password, String sourceIp, String userAgent) {
        AuthUser user = findPlatformUser(normalizeEmail(email))
                .orElseThrow(() -> new CustodyUnauthorizedException("invalid credentials"));
        return authenticate(user, password, sourceIp, userAgent, ActorType.PLATFORM_USER);
    }

    public CustodyPrincipal requireSession(String token, boolean platformRoute) {
        requireSessionToken(token);
        String tokenHash = crypto.sha256(token);
        SessionRecord session = findActiveSession(tokenHash)
                .orElseThrow(() -> new CustodyUnauthorizedException("session expired or invalid"));
        if (!"ACTIVE".equals(session.userStatus()) || !"ACTIVE".equals(session.tenantStatus())) {
            throw new CustodyForbiddenException("account or tenant is not active");
        }

        boolean platformUser = "PLATFORM_ADMIN".equals(session.role()) && session.tenantId() == null;
        if (platformRoute != platformUser) {
            throw new CustodyForbiddenException("route is outside the authenticated account scope");
        }

        sessions.touch(session.sessionId());
        return new CustodyPrincipal(
                platformUser ? ActorType.PLATFORM_USER : ActorType.TENANT_USER,
                session.userId(),
                session.tenantId(),
                session.tenantSlug(),
                session.role(),
                consoleScopes(session.role()));
    }

    public void logout(String token) {
        requireSessionToken(token);
        sessions.revoke(crypto.sha256(token));
    }

    public boolean sessionCookieSecure() {
        return properties.isSessionCookieSecure();
    }

    private static void requireSessionToken(String token) {
        if (token == null || !token.startsWith("cs_") || token.length() < 32) {
            throw new CustodyUnauthorizedException("session token required");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapPlatformAdmin() {
        String email = properties.getPlatformAdmin().getEmail();
        String password = properties.getPlatformAdmin().getPassword();
        if (email.isBlank() || password.isBlank() || users.platformAdminExists()) {
            return;
        }

        users.insertPlatformAdmin(UUID.randomUUID(), normalizeEmail(email), passwords.hash(password));
    }

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
            users.recordLoginFailure(user.id(), lockedUntil);
            throw new CustodyUnauthorizedException("invalid credentials");
        }

        users.recordLoginSuccess(user.id());
        String rawToken = "cs_" + crypto.randomSecret(32);
        Instant expiresAt = now.plus(validatedSessionTtl());
        sessions.insert(UUID.randomUUID(), user.id(), user.tenantId(), crypto.sha256(rawToken),
                sourceIp, userAgent, expiresAt);
        audits.insert(UUID.randomUUID(), user.tenantId(), actorType.name(), user.id().toString(),
                "AUTH.LOGIN", "SESSION", null, sourceIp, "{}");
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

    private Duration validatedSessionTtl() {
        Duration ttl = properties.getSessionTtl();
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalStateException("custody session TTL must be between 1 second and 7 days");
        }

        return ttl;
    }

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

    private static String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.contains("@")) {
            throw new CustodyUnauthorizedException("invalid credentials");
        }

        return value;
    }

    private Optional<AuthUser> findTenantUser(String email) {
        var user = users.findByEmail(email, false).orElse(null);
        if (user == null) return Optional.empty();
        return tenants.findById(user.tenantId()).map(tenant -> toAuthUser(user, tenant));
    }

    private Optional<AuthUser> findPlatformUser(String email) {
        return users.findByEmail(email, true).map(user -> toAuthUser(user, null));
    }

    private Optional<SessionRecord> findActiveSession(String tokenHash) {
        var session = sessions.findActive(tokenHash).orElse(null);
        if (session == null) return Optional.empty();
        var user = users.findById(session.userId()).orElse(null);
        if (user == null) return Optional.empty();
        var tenant = session.tenantId() == null ? null : tenants.findById(session.tenantId()).orElse(null);
        return Optional.of(new SessionRecord(session.id(), session.userId(), session.tenantId(),
                tenant == null ? null : tenant.slug(), user.email(), user.displayName(), user.role(),
                user.status(), tenant == null ? "ACTIVE" : tenant.status(), session.expiresAt()));
    }

    private static AuthUser toAuthUser(TenantUserRecord user, TenantRecord tenant) {
        return new AuthUser(user.id(), user.tenantId(), tenant == null ? null : tenant.slug(),
                tenant == null ? "ACTIVE" : tenant.status(), user.email(), user.displayName(),
                user.passwordHash(), user.role(), user.status(), user.failedLoginCount(), user.lockedUntil());
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
