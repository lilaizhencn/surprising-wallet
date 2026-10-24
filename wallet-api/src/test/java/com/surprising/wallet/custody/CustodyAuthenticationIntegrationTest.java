package com.surprising.wallet.custody;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.exception.CustodyUnauthorizedException;
import com.surprising.wallet.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodySessionRepository;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.CustodyTenantUserRepository;
import com.surprising.wallet.service.CustodyAuthService;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.service.CustodyPasswordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyAuthenticationIntegrationTest {
    private static final String PASSWORD = "test-only-password-123";
    private static final String EMAIL = "admin@authentication.example.test";
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private CustodyAuthService auth;
    private CustodyCryptoService crypto;
    private CustodySecurityProperties properties;
    private UUID tenantId;
    private UUID userId;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach
    void setup() throws Exception {
        var dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        properties = new CustodySecurityProperties();
        properties.setSecretMasterKey("11".repeat(32));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(CustodySecurityProperties.class, () -> properties);
        context.registerBean(CustodyTenantUserRepository.class);
        context.registerBean(CustodyTenantTableRepository.class);
        context.registerBean(CustodySessionRepository.class);
        context.registerBean(CustodyAuditRepository.class);
        context.registerBean(CustodyPasswordService.class);
        context.registerBean(CustodyCryptoService.class);
        context.registerBean(CustodyAuthService.class);
        context.refresh();
        auth = context.getBean(CustodyAuthService.class);
        crypto = context.getBean(CustodyCryptoService.class);
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        context.getBean(CustodyTenantTableRepository.class).insert(tenantId, "auth-" + tenantId, "Auth test");
        context.getBean(CustodyTenantUserRepository.class).insertTenantAdmin(
                userId, tenantId, EMAIL, "Test administrator",
                context.getBean(CustodyPasswordService.class).hash(PASSWORD));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @Test
    void failuresCommitAndLockTheAccountUntilItIsUnlocked() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThrows(CustodyUnauthorizedException.class,
                    () -> auth.tenantLogin(EMAIL, "wrong-password", "127.0.0.1", "test"));
            assertEquals(attempt, failures());
        }
        Instant lockedUntil = jdbc.queryForObject(
                "select locked_until from custody_tenant_user where id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), userId);
        assertNotNull(lockedUntil);
        assertTrue(lockedUntil.isAfter(Instant.now().plus(Duration.ofMinutes(14))));
        assertThrows(CustodyUnauthorizedException.class, this::login);
        assertEquals(5, failures());
        assertEquals(0, sessionCount());
        assertEquals(1, context.getBean(CustodyTenantUserRepository.class)
                .unlockAdministrator(tenantId, userId));
        login();
        assertEquals(0, failures());
        assertEquals(1, sessionCount());
    }

    @Test
    void loginPersistsOnlyTheTokenHashAndCreatesTheTenantAuditAndSession() {
        var result = auth.tenantLogin("  ADMIN@AUTHENTICATION.EXAMPLE.TEST  ", PASSWORD,
                "127.0.0.1", "a".repeat(600));
        assertEquals(userId, result.userId());
        assertEquals(tenantId, result.tenantId());
        assertEquals(EMAIL, result.email());
        var session = jdbc.queryForMap("select * from custody_session where tenant_user_id = ?", userId);
        assertEquals(crypto.sha256(result.token()), session.get("token_hash"));
        assertNotEquals(result.token(), session.get("token_hash"));
        assertEquals(512, session.get("user_agent").toString().length());
        assertEquals("127.0.0.1", session.get("source_ip").toString());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from custody_audit_log
                 where tenant_id = ? and actor_id = ? and actor_type = 'TENANT_USER'
                   and action = 'AUTH.LOGIN' and resource_type = 'SESSION'
                """, Integer.class, tenantId, userId.toString()));
        assertNotNull(jdbc.queryForObject("select last_login_at from custody_tenant_user where id = ?",
                java.sql.Timestamp.class, userId));
    }

    @Test
    void sessionReadsCurrentStatusesChecksRouteAndLogoutRevokesIt() {
        String token = login().token();
        jdbc.update("update custody_session set last_seen_at = now() - interval '6 minutes' where tenant_user_id = ?", userId);
        var principal = auth.requireSession(token, false);
        assertEquals(tenantId, principal.tenantId());
        assertEquals(userId, principal.actorId());
        assertEquals(ActorType.TENANT_USER, principal.actorType());
        assertTrue(jdbc.queryForObject("""
                select last_seen_at > now() - interval '1 minute'
                  from custody_session where tenant_user_id = ?
                """, Boolean.class, userId));
        assertThrows(CustodyForbiddenException.class, () -> auth.requireSession(token, true));
        jdbc.update("update custody_tenant_user set status = 'DISABLED' where id = ?", userId);
        assertThrows(CustodyForbiddenException.class, () -> auth.requireSession(token, false));
        jdbc.update("update custody_tenant_user set status = 'ACTIVE' where id = ?", userId);
        jdbc.update("update custody_tenant set status = 'SUSPENDED' where id = ?", tenantId);
        assertThrows(CustodyForbiddenException.class, () -> auth.requireSession(token, false));
        jdbc.update("update custody_tenant set status = 'ACTIVE' where id = ?", tenantId);
        auth.logout(token);
        assertThrows(CustodyUnauthorizedException.class, () -> auth.requireSession(token, false));
    }

    @Test
    void expiredSessionsCannotAuthenticate() {
        String token = login().token();
        jdbc.update("update custody_session set expires_at = now() - interval '1 second' where tenant_user_id = ?", userId);
        assertThrows(CustodyUnauthorizedException.class, () -> auth.requireSession(token, false));
    }

    @Test
    void platformBootstrapIsIdempotentAndItsSessionCannotEnterTenantRoutes() {
        jdbc.update("delete from custody_tenant_user where tenant_id is null and email = 'admin@local.test'");
        properties.getPlatformAdmin().setEmail("platform@authentication.example.test");
        properties.getPlatformAdmin().setPassword(PASSWORD);
        auth.bootstrapPlatformAdmin();
        auth.bootstrapPlatformAdmin();
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from custody_tenant_user where tenant_id is null and role = 'PLATFORM_ADMIN'
                """, Integer.class));
        var result = auth.platformLogin("PLATFORM@AUTHENTICATION.EXAMPLE.TEST", PASSWORD, "127.0.0.1", "test");
        assertNull(result.tenantId());
        assertNull(result.tenantSlug());
        assertEquals(ActorType.PLATFORM_USER, auth.requireSession(result.token(), true).actorType());
        assertThrows(CustodyForbiddenException.class, () -> auth.requireSession(result.token(), false));
        assertThrows(CustodyUnauthorizedException.class,
                () -> auth.tenantLogin("platform@authentication.example.test", PASSWORD, "127.0.0.1", "test"));
        assertThrows(CustodyUnauthorizedException.class,
                () -> auth.platformLogin(EMAIL, PASSWORD, "127.0.0.1", "test"));
    }

    @Test
    void auditFailureRollsBackLoginSuccessAndTheNewSessionWithoutAnOuterTransaction() {
        context.getBean(CustodyTenantUserRepository.class).recordLoginFailure(userId, null);
        jdbc.execute("alter table custody_audit_log rename to custody_audit_log_unavailable");
        try {
            assertThrows(RuntimeException.class, this::login);
            assertEquals(1, failures());
            assertEquals(0, sessionCount());
            assertNull(jdbc.queryForObject("select last_login_at from custody_tenant_user where id = ?",
                    java.sql.Timestamp.class, userId));
        } finally {
            jdbc.execute("alter table custody_audit_log_unavailable rename to custody_audit_log");
        }
        login();
        assertEquals(0, failures());
        assertEquals(1, sessionCount());
    }

    @Test
    void invalidTtlRollsBackTheSuccessUpdateAndDoesNotCreateASession() {
        context.getBean(CustodyTenantUserRepository.class).recordLoginFailure(userId, null);
        properties.setSessionTtl(Duration.ZERO);
        assertThrows(IllegalStateException.class, this::login);
        assertEquals(1, failures());
        assertEquals(0, sessionCount());
    }

    @Test
    void inactiveAccountsCannotLogInOrAccumulatePasswordFailures() {
        jdbc.update("update custody_tenant set status = 'SUSPENDED' where id = ?", tenantId);
        assertThrows(CustodyForbiddenException.class, this::login);
        jdbc.update("update custody_tenant set status = 'ACTIVE' where id = ?", tenantId);
        jdbc.update("update custody_tenant_user set status = 'DISABLED' where id = ?", userId);
        assertThrows(CustodyForbiddenException.class, this::login);
        assertEquals(0, failures());
        assertEquals(0, sessionCount());
    }

    private CustodyAuthService.LoginResult login() {
        return auth.tenantLogin(EMAIL, PASSWORD, "127.0.0.1", "test");
    }

    private int failures() {
        return jdbc.queryForObject("select failed_login_count from custody_tenant_user where id = ?", Integer.class, userId);
    }

    private int sessionCount() {
        return jdbc.queryForObject("select count(*) from custody_session where tenant_user_id = ?", Integer.class, userId);
    }
}
