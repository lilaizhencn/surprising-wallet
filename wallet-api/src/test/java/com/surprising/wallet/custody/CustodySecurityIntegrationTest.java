package com.surprising.wallet.custody;

import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.exception.CustodyUnauthorizedException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodySecurityIntegrationTest {
    private static final String PASSWORD = "test-only-password-123";
    private static final String TARGET = "/custody/api/v1/addresses?subject=a%2Fb&label=a+b";
    private static final byte[] BODY = "{\"memo\":\"测试\"}".getBytes(StandardCharsets.UTF_8);
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private CustodyApiKeyService keys;
    private CustodySecurityService security;
    private CustodyTenantService tenants;
    private CustodyCryptoService crypto;
    private CustodyApiNonceRepository nonces;
    private TransactionTemplate transactions;
    private CustodyPrincipal admin;
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
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        var properties = new CustodySecurityProperties();
        properties.setSecretMasterKey("22".repeat(32));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(CustodySecurityProperties.class, () -> properties);
        context.registerBean(CustodyTenantTableRepository.class);
        context.registerBean(CustodyTenantUserRepository.class);
        context.registerBean(CustodySessionRepository.class);
        context.registerBean(CustodyApiKeyRepository.class);
        context.registerBean(CustodyApiNonceRepository.class);
        context.registerBean(CustodyIpRuleRepository.class);
        context.registerBean(CustodyAuditRepository.class);
        context.registerBean(CustodyIdempotencyRepository.class);
        context.registerBean(CustodyRepository.class);
        context.registerBean(CustodyEventService.class, () -> com.surprising.wallet.testutil.CustodyTestSupport.events(jdbc));
        context.registerBean(CustodyAddressService.class, () -> mock(CustodyAddressService.class));
        context.registerBean(CustodyTransactionQueryService.class, () -> mock(CustodyTransactionQueryService.class));
        context.registerBean(CustodyPasswordService.class);
        context.registerBean(CustodyCryptoService.class);
        context.registerBean(CustodyAuthService.class);
        context.registerBean(CustodyApiKeyService.class);
        context.registerBean(CustodySecurityService.class);
        context.registerBean(CustodySecurityMaintenanceService.class);
        context.registerBean(CustodyWebhookEndpointRepository.class);
        context.registerBean(CustodyWebhookDeliveryRepository.class);
        context.registerBean(CustodyWebhookDeliveryAttemptRepository.class);
        context.registerBean(CustodyEventRepository.class);
        context.registerBean(com.surprising.wallet.gateway.CustodyWebhookHttpClient.class,
                () -> new com.surprising.wallet.gateway.CustodyWebhookHttpClient(context.getBean(CustodyCryptoService.class), "dev"));
        context.registerBean(CustodyWebhookService.class);
        context.registerBean(CustodyTenantService.class);
        context.refresh();
        keys = context.getBean(CustodyApiKeyService.class);
        security = context.getBean(CustodySecurityService.class);
        tenants = context.getBean(CustodyTenantService.class);
        crypto = context.getBean(CustodyCryptoService.class);
        nonces = context.getBean(CustodyApiNonceRepository.class);
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        context.getBean(CustodyTenantTableRepository.class).insert(tenantId, "security-" + tenantId, "Security test");
        context.getBean(CustodyTenantUserRepository.class).insertTenantAdmin(userId, tenantId,
                email(), "Administrator", context.getBean(CustodyPasswordService.class).hash(PASSWORD));
        admin = principal(tenantId, userId, "TENANT_ADMIN", Set.of("*"));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @Test
    void keySecretsAreEncryptedAndListsAndRevocationAreTenantScoped() throws Exception {
        var key = key();
        String encrypted = jdbc.queryForObject("select secret_ciphertext from custody_api_key where id = ?", String.class, key.id());
        assertNotEquals(key.secret(), encrypted);
        assertEquals(key.secret(), crypto.decrypt(encrypted));
        assertEquals(1, keys.list(tenantId).size());
        assertTrue(keys.list(UUID.randomUUID()).isEmpty());
        String json = mapper.writeValueAsString(keys.list(tenantId));
        assertFalse(json.contains(key.secret()));
        assertFalse(json.contains("secretCiphertext"));
        assertFalse(json.contains("secret_ciphertext"));
        assertThrows(IllegalArgumentException.class,
                () -> keys.revoke(UUID.randomUUID(), userId, key.id(), "127.0.0.1"));
        assertEquals("ACTIVE", keyStatus(key));
        keys.revoke(tenantId, userId, key.id(), "127.0.0.1");
        assertEquals("REVOKED", keyStatus(key));
        assertNotNull(keys.list(tenantId).getFirst().revokedAt());
        assertEquals(2, auditCount());
    }

    @Test
    void hmacBindsTheRawBodyAndRequestTargetAndRejectsReplay() {
        var key = key();
        long timestamp = Instant.now().getEpochSecond();
        String nonce = nonce();
        String signature = signature(key, timestamp, nonce);
        assertThrows(CustodyUnauthorizedException.class, () -> keys.authenticate(key.keyId(), timestamp, nonce,
                signature, "post", TARGET, new byte[0], "127.0.0.1"));
        assertThrows(CustodyUnauthorizedException.class, () -> keys.authenticate(key.keyId(), timestamp, nonce,
                signature, "post", TARGET.replace("%2F", "/"), BODY, "127.0.0.1"));
        assertEquals(0, nonceCount());
        var authenticated = keys.authenticate(key.keyId(), timestamp, nonce, signature, "post", TARGET, BODY, "127.0.0.1");
        assertEquals(tenantId, authenticated.tenantId());
        assertEquals(key.id(), authenticated.actorId());
        assertEquals(ActorType.API_KEY, authenticated.actorType());
        assertTrue(authenticated.hasScope("*"));
        assertNotNull(keys.list(tenantId).getFirst().lastUsedAt());
        assertEquals("127.0.0.1", keys.list(tenantId).getFirst().lastUsedIp());
        assertThrows(CustodyUnauthorizedException.class, () -> keys.authenticate(key.keyId(), timestamp, nonce,
                signature, "post", TARGET, BODY, "127.0.0.1"));
        assertEquals(1, nonceCount());
    }

    @Test
    void expiredRevokedKeysAndSuspendedTenantsCannotConsumeNonces() {
        var expired = key();
        jdbc.update("update custody_api_key set expires_at = now() - interval '1 second' where id = ?", expired.id());
        assertThrows(CustodyUnauthorizedException.class, () -> authenticate(expired, "127.0.0.1"));
        var revoked = key();
        keys.revoke(tenantId, userId, revoked.id(), "127.0.0.1");
        assertThrows(CustodyUnauthorizedException.class, () -> authenticate(revoked, "127.0.0.1"));
        var active = key();
        jdbc.update("update custody_tenant set status = 'SUSPENDED' where id = ?", tenantId);
        assertThrows(CustodyForbiddenException.class, () -> authenticate(active, "127.0.0.1"));
        assertEquals(0, nonceCount());
    }

    @Test
    void clockWindowAndNonceShapeAreCheckedBeforeClaiming() {
        var key = key();
        long now = Instant.now().getEpochSecond();
        for (long timestamp : new long[]{now - 600, now + 600, Long.MIN_VALUE, Long.MAX_VALUE, now + Long.MIN_VALUE}) {
            String nonce = nonce();
            assertThrows(CustodyUnauthorizedException.class, () -> keys.authenticate(key.keyId(), timestamp, nonce,
                    signature(key, timestamp, nonce), "post", TARGET, BODY, "127.0.0.1"));
        }
        for (String nonce : new String[]{"short", "bad nonce with spaces", "x".repeat(129)}) {
            assertThrows(CustodyUnauthorizedException.class, () -> keys.authenticate(key.keyId(), now, nonce,
                    signature(key, now, nonce), "post", TARGET, BODY, "127.0.0.1"));
        }
        assertEquals(0, nonceCount());
        assertNull(keys.list(tenantId).getFirst().lastUsedAt());
    }

    @Test
    void ipAllowlistIsTenantScopedAndDisabledRulesDoNotAuthorize() {
        var key = key();
        assertThrows(IllegalStateException.class, () -> security.updateIpEnforcement(admin, true, "127.0.0.1"));
        var rule = security.addIpRule(admin, "Trusted", "192.0.2.0/24", "127.0.0.1");
        var disabled = security.addIpRule(admin, "Disabled", "198.51.100.0/24", "127.0.0.1");
        jdbc.update("update custody_ip_rule set enabled = false where id = ?", disabled.id());
        security.updateIpEnforcement(admin, true, "127.0.0.1");
        assertThrows(CustodyForbiddenException.class, () -> authenticate(key, "198.51.100.5"));
        assertThrows(CustodyForbiddenException.class, () -> authenticate(key, "203.0.113.5"));
        assertEquals(0, nonceCount());
        assertEquals(tenantId, authenticate(key, "192.0.2.8").tenantId());
        assertEquals(1, nonceCount());
        assertThrows(IllegalArgumentException.class,
                () -> security.deleteIpRule(principal(UUID.randomUUID(), userId, "TENANT_ADMIN", Set.of("*")), rule.id(), "127.0.0.1"));
        assertEquals(2, context.getBean(CustodyIpRuleRepository.class).list(tenantId).size());
    }

    @Test
    void keyCreationAndRevocationRollbackWhenAuditFails() {
        withoutAudit(() -> {
            assertThrows(RuntimeException.class, this::key);
            assertTrue(keys.list(tenantId).isEmpty());
        });
        var key = key();
        withoutAudit(() -> {
            assertThrows(RuntimeException.class, () -> keys.revoke(tenantId, userId, key.id(), "127.0.0.1"));
            assertEquals("ACTIVE", keyStatus(key));
            assertNull(keys.list(tenantId).getFirst().revokedAt());
        });
        assertEquals(1, auditCount());
    }

    @Test
    void ipChangesAndEnforcementRollbackWhenAuditFails() {
        var rule = security.addIpRule(admin, "Existing", "192.0.2.0/24", "127.0.0.1");
        withoutAudit(() -> {
            assertThrows(RuntimeException.class, () -> security.updateIpEnforcement(admin, true, "127.0.0.1"));
            assertFalse(context.getBean(CustodyTenantTableRepository.class).findById(tenantId).orElseThrow().ipAllowlistEnabled());
            assertThrows(RuntimeException.class, () -> security.deleteIpRule(admin, rule.id(), "127.0.0.1"));
            assertThrows(RuntimeException.class, () -> security.addIpRule(admin, "New", "198.51.100.0/24", "127.0.0.1"));
            assertEquals(1, context.getBean(CustodyIpRuleRepository.class).list(tenantId).size());
        });
        assertEquals(1, auditCount());
    }

    @Test
    void tenantSuspensionAndSessionRevocationShareTheAuditTransaction() {
        String token = context.getBean(CustodyAuthService.class).tenantLogin(email(), PASSWORD, "127.0.0.1", "test").token();
        withoutAudit(() -> {
            assertThrows(RuntimeException.class, () -> tenants.updateStatus(platform(), tenantId, "SUSPENDED", "127.0.0.1"));
            assertEquals("ACTIVE", context.getBean(CustodyTenantTableRepository.class).findById(tenantId).orElseThrow().status());
            assertEquals(1, context.getBean(CustodySessionRepository.class).countActive(tenantId));
        });
        tenants.updateStatus(platform(), tenantId, "SUSPENDED", "127.0.0.1");
        assertEquals(0, context.getBean(CustodySessionRepository.class).countActive(tenantId));
        assertThrows(CustodyUnauthorizedException.class, () -> context.getBean(CustodyAuthService.class).requireSession(token, false));
        assertEquals("1", jdbc.queryForObject("""
                select details->>'revokedSessions' from custody_audit_log
                 where tenant_id = ? and action = 'TENANT.STATUS_CHANGE'
                """, String.class, tenantId));
    }

    @Test
    void administratorUnlockRequiresPlatformTenantRoleAndActiveUser() {
        lockAdministrator();
        assertThrows(CustodyForbiddenException.class, () -> tenants.unlockAdministrator(admin, tenantId, userId, "127.0.0.1"));
        UUID foreignTenant = UUID.randomUUID();
        UUID foreignUser = UUID.randomUUID();
        var users = context.getBean(CustodyTenantUserRepository.class);
        context.getBean(CustodyTenantTableRepository.class).insert(foreignTenant, "foreign-" + foreignTenant, "Foreign tenant");
        users.insertTenantAdmin(foreignUser, foreignTenant, foreignUser + "@security.example.test", "Foreign administrator",
                users.findById(userId).orElseThrow().passwordHash());
        jdbc.update("update custody_tenant_user set failed_login_count = 5, locked_until = now() + interval '15 minutes' where id = ?", foreignUser);
        assertEquals(0, users.unlockAdministrator(tenantId, foreignUser));
        assertThrows(IllegalArgumentException.class, () -> tenants.unlockAdministrator(platform(), tenantId, foreignUser, "127.0.0.1"));
        assertEquals(5, users.findView(foreignTenant, foreignUser).orElseThrow().failedLoginCount());
        jdbc.update("update custody_tenant_user set role = 'VIEWER' where id = ?", userId);
        assertThrows(IllegalArgumentException.class, () -> tenants.unlockAdministrator(platform(), tenantId, userId, "127.0.0.1"));
        jdbc.update("update custody_tenant_user set role = 'TENANT_ADMIN', status = 'DISABLED' where id = ?", userId);
        assertThrows(IllegalArgumentException.class, () -> tenants.unlockAdministrator(platform(), tenantId, userId, "127.0.0.1"));
        jdbc.update("update custody_tenant_user set status = 'ACTIVE' where id = ?", userId);
        var user = tenants.unlockAdministrator(platform(), tenantId, userId, "127.0.0.1");
        assertEquals(0, user.failedLoginCount());
        assertNull(user.lockedUntil());
        assertEquals(1, auditCount());
    }

    @Test
    void administratorUnlockRollsBackWhenAuditFails() {
        lockAdministrator();
        withoutAudit(() -> {
            assertThrows(RuntimeException.class, () -> tenants.unlockAdministrator(platform(), tenantId, userId, "127.0.0.1"));
            var user = context.getBean(CustodyTenantUserRepository.class).findView(tenantId, userId).orElseThrow();
            assertEquals(5, user.failedLoginCount());
            assertTrue(user.lockedUntil().isAfter(Instant.now()));
        });
        assertEquals(0, tenants.unlockAdministrator(platform(), tenantId, userId, "127.0.0.1").failedLoginCount());
    }

    @Test
    void duplicateNonceDoesNotAbortAnOuterTransactionAndIsScopedByKey() {
        var first = key();
        var second = key();
        String nonce = nonce();
        Instant expiry = Instant.now().plusSeconds(600);
        transactions.executeWithoutResult(status -> {
            assertTrue(nonces.reserve(first.keyId(), nonce, expiry));
            assertFalse(nonces.reserve(first.keyId(), nonce, expiry));
            assertTrue(nonces.reserve(second.keyId(), nonce, expiry));
            context.getBean(CustodyAuditRepository.class).insert(UUID.randomUUID(), tenantId, "TENANT_USER",
                    userId.toString(), "NONCE.PROBE", "API_KEY", first.id().toString(), "127.0.0.1", "{}");
        });
        assertEquals(2, nonceCount());
        assertEquals(3, auditCount());
    }

    @Test
    void concurrentNonceClaimsHaveExactlyOneWinner() throws Exception {
        var key = key();
        String nonce = nonce();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 24).mapToObj(index -> executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return nonces.reserve(key.keyId(), nonce, Instant.now().plusSeconds(600));
            })).toList();
            start.countDown();
            int winners = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
        assertEquals(1, nonceCount());
    }

    @Test
    void maintenanceKeepsLiveRecordsAndOnlyRemovesEligibleExpiredRows() {
        var key = key();
        nonces.reserve(key.keyId(), "expired_nonce_123456", Instant.now().minusSeconds(60));
        nonces.reserve(key.keyId(), "live_nonce_123456789", Instant.now().plusSeconds(600));
        var idempotencies = context.getBean(CustodyIdempotencyRepository.class);
        idempotencies.begin(tenantId, "expired", "TEST", "hash", Instant.now().minusSeconds(60));
        idempotencies.begin(tenantId, "live", "TEST", "hash", Instant.now().plusSeconds(600));
        var sessions = context.getBean(CustodySessionRepository.class);
        sessions.insert(UUID.randomUUID(), userId, tenantId, "old-expired", "127.0.0.1", "test", Instant.now().minusSeconds(8 * 86400));
        sessions.insert(UUID.randomUUID(), userId, tenantId, "recent-expired", "127.0.0.1", "test", Instant.now().minusSeconds(60));
        sessions.insert(UUID.randomUUID(), userId, tenantId, "live", "127.0.0.1", "test", Instant.now().plusSeconds(86400));
        sessions.insert(UUID.randomUUID(), userId, tenantId, "old-revoked", "127.0.0.1", "test", Instant.now().plusSeconds(86400));
        jdbc.update("update custody_session set revoked_at = now() - interval '8 days' where token_hash = 'old-revoked'");
        assertEquals(4, context.getBean(CustodySecurityMaintenanceService.class).cleanupExpiredSecurityRows());
        assertEquals(1, nonceCount());
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_idempotency_key where tenant_id = ?", Integer.class, tenantId));
        assertEquals(2, jdbc.queryForObject("select count(*) from custody_session where tenant_id = ?", Integer.class, tenantId));
        assertEquals(1, sessions.countActive(tenantId));
    }

    @Test
    void publicViewsHaveConsistentFieldsAndTheBaselineHasNoUnusedSecretVersionColumns() throws Exception {
        key();
        var rule = security.addIpRule(admin, "Trusted", "192.0.2.0/24", "127.0.0.1");
        assertEquals(rule, context.getBean(CustodyIpRuleRepository.class).list(tenantId).getFirst());
        String ruleJson = mapper.writeValueAsString(rule);
        assertTrue(ruleJson.contains("createdAt"));
        assertFalse(ruleJson.contains("created_at"));
        String userJson = mapper.writeValueAsString(context.getBean(CustodyTenantUserRepository.class).listByTenant(tenantId));
        assertFalse(userJson.contains("password"));
        assertFalse(userJson.contains("pbkdf2"));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = 'public' and table_name in ('custody_api_key', 'custody_webhook_endpoint')
                   and column_name = 'secret_version'
                """, Integer.class));
    }

    private CustodyApiKeyService.CreatedApiKey key() {
        return keys.create(tenantId, userId, "  Test backend  ", "127.0.0.1");
    }

    private CustodyPrincipal authenticate(CustodyApiKeyService.CreatedApiKey key, String ip) {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = nonce();
        return keys.authenticate(key.keyId(), timestamp, nonce, signature(key, timestamp, nonce), "post", TARGET, BODY, ip);
    }

    private String signature(CustodyApiKeyService.CreatedApiKey key, long timestamp, String nonce) {
        String canonical = timestamp + "\n" + nonce + "\n" + "post".toUpperCase(Locale.ROOT) + "\n" + TARGET + "\n" + crypto.sha256(BODY);
        return crypto.hmacSha256(key.secret(), canonical);
    }

    private String nonce() { return crypto.randomSecret(18); }
    private String email() { return "admin-" + userId + "@security.example.test"; }
    private String keyStatus(CustodyApiKeyService.CreatedApiKey key) {
        return jdbc.queryForObject("select status from custody_api_key where id = ?", String.class, key.id());
    }
    private int nonceCount() { return jdbc.queryForObject("select count(*) from custody_api_nonce", Integer.class); }
    private int auditCount() { return context.getBean(CustodyAuditRepository.class).list(tenantId, 500, 0).size(); }
    private CustodyPrincipal platform() { return new CustodyPrincipal(ActorType.PLATFORM_USER, UUID.randomUUID(), null, null, "PLATFORM_ADMIN", Set.of("*")); }
    private CustodyPrincipal principal(UUID tenant, UUID user, String role, Set<String> scopes) {
        return new CustodyPrincipal(ActorType.TENANT_USER, user, tenant, "test", role, scopes);
    }
    private void lockAdministrator() {
        jdbc.update("update custody_tenant_user set failed_login_count = 5, locked_until = now() + interval '15 minutes' where id = ?", userId);
    }
    private void withoutAudit(Runnable action) {
        jdbc.execute("alter table custody_audit_log rename to custody_audit_log_unavailable");
        try { action.run(); }
        finally { jdbc.execute("alter table custody_audit_log_unavailable rename to custody_audit_log"); }
    }
}
