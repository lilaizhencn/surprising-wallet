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
        context.registerBean(com.surprising.wallet.service.CustodyVerificationMailService.class);
        context.registerBean(com.surprising.wallet.service.CustodyLoginSecurityService.class);
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

    private com.surprising.wallet.service.CustodyLoginSecurityService security() {
        return context.getBean(com.surprising.wallet.service.CustodyLoginSecurityService.class);
    }

    @Test
    void requiredAuthenticatorEnrollmentCannotAccessBusinessUntilConfirmedAndRotatesTheCookie() {
        var original=login();
        var actor=auth.requireSession(original.token(),false);
        security().policy(actor,userId,false,true,"127.0.0.1");
        assertThrows(CustodyUnauthorizedException.class,()->auth.requireSession(original.token(),false));
        var pending=login();
        assertTrue(pending.verificationRequired());
        assertTrue(pending.totpSetupRequired());
        assertThrows(CustodyUnauthorizedException.class,()->auth.requireSession(pending.token(),false));
        var setup=security().setup(pending.token(),null,false,"127.0.0.1");
        assertTrue(setup.uri().startsWith("otpauth://totp/"));
        var repeated=security().setup(pending.token(),null,false,"127.0.0.1");
        assertEquals(setup.secret(),repeated.secret());
        long step=Instant.now().getEpochSecond()/30;
        var finalLogin=security().verify(pending.token(),null,com.surprising.wallet.common.security.Totp.atStep(setup.secret(),step),"127.0.0.1");
        assertFalse(finalLogin.verificationRequired());
        assertNotEquals(pending.token(),finalLogin.token());
        assertThrows(CustodyUnauthorizedException.class,()->auth.requireSession(pending.token(),false));
        assertEquals(userId,auth.requireSession(finalLogin.token(),false).actorId());
        String stored=jdbc.queryForObject("select totp_ciphertext from custody_tenant_user where id=?",String.class,userId);
        assertFalse(stored.contains(setup.secret()));
        assertFalse(jdbc.queryForObject("select string_agg(details::text,',') from custody_audit_log",String.class).contains(setup.secret()));
        var second=login();
        assertTrue(second.verificationRequired());
        assertFalse(second.totpSetupRequired());
        assertThrows(CustodyUnauthorizedException.class,()->security().verify(second.token(),null,com.surprising.wallet.common.security.Totp.atStep(setup.secret(),step),"127.0.0.1"));
        var next=security().verify(second.token(),null,com.surprising.wallet.common.security.Totp.atStep(setup.secret(),step+1),"127.0.0.1");
        assertFalse(next.verificationRequired());
        assertThrows(CustodyForbiddenException.class,()->security().unbind(next.token(),PASSWORD,"000000","127.0.0.1"));
    }

    @Test
    void optionalBindingStillRequiresTotpAndRepeatedBadCodesCommitAttemptsAndLockTheAccount() {
        var initial=login();
        var setup=security().setup(initial.token(),PASSWORD,true,"127.0.0.1");
        String code=com.surprising.wallet.common.security.Totp.atStep(setup.secret(),Instant.now().getEpochSecond()/30);
        security().confirm(initial.token(),code,"127.0.0.1");
        assertTrue(security().own(auth.requireSession(initial.token(),false)).totpBound());
        var pending=login();
        assertTrue(pending.totpRequired());
        for(int i=0;i<5;i++) assertThrows(CustodyUnauthorizedException.class,()->security().verify(pending.token(),null,"invalid","127.0.0.1"));
        assertEquals(5,failures());
        assertThrows(CustodyUnauthorizedException.class,this::login);
        assertEquals(5,jdbc.queryForObject("select count(*) from custody_audit_log where action='AUTH.VERIFICATION_FAILED'",Integer.class));
        assertThrows(CustodyUnauthorizedException.class,()->security().setup(pending.token(),null,false,"127.0.0.1"));
    }

    @Test
    void emailCannotBeRequiredWhenDeliveryIsUnconfiguredAndPolicyAndResetAreTenantScoped() {
        var initial=login();
        var actor=auth.requireSession(initial.token(),false);
        assertFalse(security().own(actor).emailDeliveryConfigured());
        assertThrows(IllegalArgumentException.class,()->security().policy(actor,userId,true,false,"127.0.0.1"));
        assertFalse(security().own(actor).emailRequired());
        UUID otherTenant=UUID.randomUUID(),otherUser=UUID.randomUUID();
        context.getBean(CustodyTenantTableRepository.class).insert(otherTenant,"other-"+otherTenant,"Other");
        context.getBean(CustodyTenantUserRepository.class).insertMember(otherUser,otherTenant,"other@example.test","Other",context.getBean(CustodyPasswordService.class).hash(PASSWORD),"OPERATOR");
        assertThrows(CustodyForbiddenException.class,()->security().policy(actor,otherUser,false,true,"127.0.0.1"));
        assertThrows(CustodyForbiddenException.class,()->security().reset(actor,otherUser,"lost phone","127.0.0.1"));
    }

    @Test
    void emailThenAuthenticatorIsSequentialAndEmailCodesAreBoundToTheirPendingCookie() {
        var mail=context.getBean(com.surprising.wallet.service.CustodyVerificationMailService.class);
        // Test mail configuration without external SMTP or delivery of real messages.
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test-mail",java.util.Map.of(
                "sw.wallet.custody.email-verification.enabled","true","sw.wallet.custody.email-verification.from","test@example.test","spring.mail.host","localhost")));
        context.getBeanFactory().registerSingleton("testMailSender",new org.springframework.mail.javamail.JavaMailSenderImpl());
        var actor=auth.requireSession(login().token(),false);
        security().policy(actor,userId,true,true,"127.0.0.1");
        var first=login();
        assertThrows(IllegalArgumentException.class,()->security().setup(first.token(),null,false,"127.0.0.1"));
        var delivery=security().prepareEmail(first.token(),"127.0.0.1");
        assertEquals(EMAIL,delivery.recipient());
        assertFalse(jdbc.queryForObject("select email_code_hash from custody_session where token_hash=?",String.class,crypto.sha256(first.token())).equals(delivery.code()));
        assertThrows(IllegalArgumentException.class,()->security().prepareEmail(first.token(),"127.0.0.1"));
        var second=login();
        assertThrows(CustodyUnauthorizedException.class,()->security().verify(second.token(),delivery.code(),null,"127.0.0.1"));
        var emailVerified=security().verify(first.token(),delivery.code(),null,"127.0.0.1");
        assertTrue(emailVerified.verificationRequired());assertTrue(emailVerified.emailVerified());
        assertThrows(CustodyUnauthorizedException.class,()->auth.requireSession(first.token(),false));
        var setup=security().setup(first.token(),null,false,"127.0.0.1");
        var complete=security().verify(first.token(),null,com.surprising.wallet.common.security.Totp.atStep(setup.secret(),Instant.now().getEpochSecond()/30),"127.0.0.1");
        assertFalse(complete.verificationRequired());assertNotNull(auth.requireSession(complete.token(),false));
    }

    @Test
    void pendingCookieIsRejectedByTheActualBusinessAuthenticationFilter() throws Exception {
        var actor=auth.requireSession(login().token(),false);
        security().policy(actor,userId,false,true,"127.0.0.1");
        String pending=login().token();
        var filter=new com.surprising.wallet.filter.CustodyConsoleAuthenticationFilter(auth,new tools.jackson.databind.ObjectMapper());
        var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/custody/console/v1/withdrawals");
        request.setCookies(new jakarta.servlet.http.Cookie(com.surprising.wallet.model.CustodySessionCookie.NAME,pending));
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(request,response,(r,s)->reached.set(true));
        assertEquals(401,response.getStatus());assertFalse(reached.get());
        var verify=new org.springframework.mock.web.MockHttpServletRequest("POST","/custody/console/v1/auth/verify");
        filter.doFilter(verify,new org.springframework.mock.web.MockHttpServletResponse(),(r,s)->reached.set(true));
        assertTrue(reached.get());
    }

    @Test
    void administratorResetsOnlyOwnedSubaccountsAndKeepsTheirMandatoryBindingPolicy() {
        var admin=auth.requireSession(login().token(),false);
        UUID member=UUID.randomUUID();
        context.getBean(CustodyTenantUserRepository.class).insertMember(member,tenantId,"member@security.test","Member",context.getBean(CustodyPasswordService.class).hash(PASSWORD),"OPERATOR");
        var initial=auth.tenantLogin("member@security.test",PASSWORD,"127.0.0.1","test");
        var operator=auth.requireSession(initial.token(),false);
        assertThrows(CustodyForbiddenException.class,()->security().policy(operator,member,false,true,"127.0.0.1"));
        security().policy(admin,member,false,true,"127.0.0.1");
        var pending=auth.tenantLogin("member@security.test",PASSWORD,"127.0.0.1","test");
        var setup=security().setup(pending.token(),null,false,"127.0.0.1");
        var complete=security().verify(pending.token(),null,com.surprising.wallet.common.security.Totp.atStep(setup.secret(),Instant.now().getEpochSecond()/30),"127.0.0.1");
        assertThrows(IllegalArgumentException.class,()->security().reset(admin,member," ","127.0.0.1"));
        security().reset(admin,member,"Lost device; identity checked","127.0.0.1");
        assertThrows(CustodyUnauthorizedException.class,()->auth.requireSession(complete.token(),false));
        var again=auth.tenantLogin("member@security.test",PASSWORD,"127.0.0.1","test");
        assertTrue(again.totpSetupRequired()); assertTrue(again.verificationRequired());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where action='SECURITY.TOTP_RESET' and resource_id=?",Integer.class,member.toString()));
    }

    @Test
    void onlyPlatformAuthorityCanRecoverATenantAdministratorsAuthenticator() {
        var admin=auth.requireSession(login().token(),false);
        assertThrows(CustodyForbiddenException.class,()->security().recoverAdministrator(admin,tenantId,userId,"lost phone","127.0.0.1"));
        security().policy(admin,userId,false,true,"127.0.0.1");
        var pending=login();var setup=security().setup(pending.token(),null,false,"127.0.0.1");
        var complete=security().verify(pending.token(),null,com.surprising.wallet.common.security.Totp.atStep(setup.secret(),Instant.now().getEpochSecond()/30),"127.0.0.1");
        jdbc.update("delete from custody_tenant_user where tenant_id is null and email='admin@local.test'");
        properties.getPlatformAdmin().setEmail("recovery@platform.test");properties.getPlatformAdmin().setPassword(PASSWORD);auth.bootstrapPlatformAdmin();
        var platform=auth.requireSession(auth.platformLogin("recovery@platform.test",PASSWORD,"127.0.0.1","test").token(),true);
        assertThrows(IllegalArgumentException.class,()->security().recoverAdministrator(platform,tenantId,userId," ","127.0.0.1"));
        security().recoverAdministrator(platform,tenantId,userId,"Identity verified for lost device","127.0.0.1");
        assertThrows(CustodyUnauthorizedException.class,()->auth.requireSession(complete.token(),false));
        assertTrue(login().totpSetupRequired());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where action='SECURITY.ADMIN_TOTP_RESET' and tenant_id=?",Integer.class,tenantId));
    }

    @Test
    void authenticatorAndVerificationStateRollbackTogetherWhenMandatoryAuditFails() {
        var admin=auth.requireSession(login().token(),false);
        security().policy(admin,userId,false,true,"127.0.0.1");
        var pending=login();var setup=security().setup(pending.token(),null,false,"127.0.0.1");
        String code=com.surprising.wallet.common.security.Totp.atStep(setup.secret(),Instant.now().getEpochSecond()/30);
        jdbc.execute("create function reject_security_audit() returns trigger language plpgsql as $$ begin if NEW.action='SECURITY.TOTP_BOUND' then raise exception 'synthetic audit failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger reject_security_audit before insert on custody_audit_log for each row execute function reject_security_audit()");
        assertThrows(org.springframework.dao.DataAccessException.class,()->security().verify(pending.token(),null,code,"127.0.0.1"));
        assertNull(jdbc.queryForObject("select totp_ciphertext from custody_tenant_user where id=?",String.class,userId));
        assertFalse(jdbc.queryForObject("select verified from custody_session where token_hash=?",Boolean.class,crypto.sha256(pending.token())));
        jdbc.execute("drop trigger reject_security_audit on custody_audit_log");jdbc.execute("drop function reject_security_audit()");
        assertFalse(security().verify(pending.token(),null,code,"127.0.0.1").verificationRequired());
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
