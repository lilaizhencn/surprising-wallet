package com.surprising.wallet.custody.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.surprising.wallet.custody.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.custody.repository.CustodyRepository.AuthUser;
import com.surprising.wallet.custody.repository.CustodyRepository.SessionRecord;
import jakarta.servlet.http.HttpServletRequest;
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
import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.model.CustodySecurityProperties;
import com.surprising.wallet.custody.model.CustodySessionCookie;
import com.surprising.wallet.custody.exception.CustodyUnauthorizedException;

@Service
public class CustodyAuthService {
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final CustodyRepository repository;
    private final CustodyPasswordService passwords;
    private final CustodyCryptoService crypto;
    private final CustodySecurityProperties properties;

    public CustodyAuthService(CustodyRepository repository, CustodyPasswordService passwords,
                              CustodyCryptoService crypto, CustodySecurityProperties properties) {
        this.repository = repository;
        this.passwords = passwords;
        this.crypto = crypto;
        this.properties = properties;
    }

    @Transactional(
            rollbackFor = Throwable.class,
            noRollbackFor = {CustodyUnauthorizedException.class, CustodyForbiddenException.class})
    public LoginResult tenantLogin(String email, String password,
                                   String sourceIp, String userAgent) {
        AuthUser user = repository.findTenantUser(normalizeEmail(email))
                .orElseThrow(() -> new CustodyUnauthorizedException("invalid credentials"));
        return authenticate(user, password, sourceIp, userAgent, ActorType.TENANT_USER);
    }

    @Transactional(
            rollbackFor = Throwable.class,
            noRollbackFor = {CustodyUnauthorizedException.class, CustodyForbiddenException.class})
    public LoginResult platformLogin(String email, String password, String sourceIp, String userAgent) {
        AuthUser user = repository.findPlatformUser(normalizeEmail(email))
                .orElseThrow(() -> new CustodyUnauthorizedException("invalid credentials"));
        return authenticate(user, password, sourceIp, userAgent, ActorType.PLATFORM_USER);
    }

    public CustodyPrincipal requireSession(HttpServletRequest request, boolean platformRoute) {
        String token = CustodySessionCookie.read(request.getCookies());
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

    public void logout(HttpServletRequest request) {
        repository.revokeSession(crypto.sha256(CustodySessionCookie.read(request.getCookies())));
    }

    public boolean sessionCookieSecure() {
        return properties.isSessionCookieSecure();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapPlatformAdmin() {
        String email = properties.getPlatformAdmin().getEmail();
        String password = properties.getPlatformAdmin().getPassword();
        if (email.isBlank() || password.isBlank() || repository.platformAdminExists()) {
            return;
        }
        repository.insertPlatformAdmin(UUID.randomUUID(), normalizeEmail(email), passwords.hash(password));
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

    public record LoginResult(
            @JsonIgnore String token,
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
