package com.surprising.wallet.custody;

import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyEventService;
import com.surprising.wallet.service.CustodyWebhookService;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.gateway.CustodyWebhookHttpClient;
import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.CustodyWebhookRetryPolicy;
import com.surprising.wallet.model.custody.WebhookDeliveryTask;
import com.surprising.wallet.service.CustodyWebhookDispatchService;
import com.sun.net.httpserver.HttpServer;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyWebhookLifecycleIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private CustodyWebhookService webhooks;
    private UUID endpointId;
    private CustodyCryptoService crypto;
    private ObjectMapper mapper;
    private RecordingHttpClient transport;
    private TransactionTemplate transactions;
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach
    void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(CustodyTenantTableRepository.class);
        context.registerBean(CustodyTenantUserRepository.class);
        context.registerBean(CustodyApiKeyRepository.class);
        context.registerBean(CustodySessionRepository.class);
        context.registerBean(CustodyIpRuleRepository.class);
        context.registerBean(CustodyEventRepository.class);
        context.registerBean(CustodyWebhookEndpointRepository.class);
        context.registerBean(CustodyWebhookDeliveryRepository.class);
        context.registerBean(CustodyEventService.class);
        context.registerBean(CustodyWebhookDeliveryAttemptRepository.class);
        context.registerBean(CustodyAuditRepository.class);
        var properties = new CustodySecurityProperties();
        properties.setSecretMasterKey("22".repeat(32));
        context.registerBean(CustodySecurityProperties.class, () -> properties);
        context.registerBean(ObjectMapper.class, () -> new CustodyJacksonConfiguration().custodyObjectMapper());
        context.registerBean(CustodyCryptoService.class);
        context.registerBean(CustodyWebhookHttpClient.class, () -> new RecordingHttpClient(context.getBean(CustodyCryptoService.class)));
        context.registerBean(CustodyWebhookService.class);
        context.registerBean(CustodyWebhookRetryPolicy.class);
        context.registerBean("custodyWebhookExecutor", TaskExecutor.class, () -> Runnable::run);
        context.registerBean(CustodyWebhookDispatchService.class);
        context.refresh();
        webhooks = context.getBean(CustodyWebhookService.class);
        crypto = context.getBean(CustodyCryptoService.class);
        mapper = context.getBean(ObjectMapper.class);
        transport = (RecordingHttpClient) context.getBean(CustodyWebhookHttpClient.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        endpointId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_webhook_endpoint(id, tenant_id, name, url, secret_ciphertext, status, verified_at)
                values (?, ?, 'Lifecycle test', 'http://127.0.0.1:1/hook', ?, 'ACTIVE', now())
                """, endpointId, TENANT, crypto.encrypt("test-hook-secret"));
        context.getBean(CustodyEventService.class).publish(UUID.randomUUID(), TENANT, "DEPOSIT.CONFIRMED",
                "DEPOSIT", "lifecycle-deposit", "{\"ok\":true}", true);
    }

    @AfterEach
    void close() { if (context != null) context.close(); }

    @Test
    void firstClaimReturnsTheCommittedAttemptAndGlobalOrdinal() {
        var task = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        assertEquals(1, task.attemptCount());
        assertEquals(1, task.totalAttemptCount());
        assertEquals(task.attemptCount(), jdbc.queryForObject(
                "select attempt_count from custody_webhook_delivery where id = ?", Integer.class, task.id()));
        assertEquals(task.totalAttemptCount(), jdbc.queryForObject(
                "select attempt_number from custody_webhook_delivery_attempt where id = ?", Integer.class, task.attemptId()));
    }

    @Test
    void anOldTaskCannotFinishALeaseRecoveredByTheSameWorkerOrTouchTheEndpoint() {
        var stale = webhooks.claimWebhookDeliveries("same-worker", 1).getFirst();
        jdbc.update("update custody_webhook_delivery set locked_at = now() - interval '6 minutes' where id = ?", stale.id());
        var recovered = webhooks.claimWebhookDeliveries("same-worker", 1).getFirst();
        assertTrue(recovered.totalAttemptCount() > stale.totalAttemptCount());
        webhooks.markWebhookDelivered(stale, 200, "late response", 360_000);
        assertEquals("DELIVERING", jdbc.queryForObject(
                "select status from custody_webhook_delivery where id = ?", String.class, recovered.id()));
        assertEquals("IN_PROGRESS", jdbc.queryForObject(
                "select status from custody_webhook_delivery_attempt where id = ?", String.class, recovered.attemptId()));
        assertNull(jdbc.queryForObject("select last_delivery_at from custody_webhook_endpoint where id = ?",
                java.sql.Timestamp.class, endpointId));
        webhooks.markWebhookDelivered(recovered, 204, "", 1);
        assertEquals("DELIVERED", jdbc.queryForObject(
                "select status from custody_webhook_delivery where id = ?", String.class, recovered.id()));
    }

    @Test
    void aTerminalFailureHasNoNextAttemptAndRemainsDurable() {
        var task = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        webhooks.markWebhookFailed(task, 500, "terminal failure", "", null, true, 1);
        assertEquals("FAILED", jdbc.queryForObject(
                "select status from custody_webhook_delivery where id = ?", String.class, task.id()));
        assertNull(jdbc.queryForObject("select next_attempt_at from custody_webhook_delivery where id = ?",
                java.sql.Timestamp.class, task.id()));
        assertEquals("FAILED", jdbc.queryForObject(
                "select status from custody_webhook_delivery_attempt where id = ?", String.class, task.attemptId()));
    }

    @Test
    void completionReceiptFailureRollsBackTheDeliveryWithoutAnOuterTransaction() {
        var task = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        jdbc.execute("alter table custody_webhook_delivery_attempt rename to custody_webhook_delivery_attempt_unavailable");
        try {
            assertThrows(RuntimeException.class, () -> webhooks.markWebhookDelivered(task, 200, "ok", 1));
            assertEquals("DELIVERING", jdbc.queryForObject(
                    "select status from custody_webhook_delivery where id = ?", String.class, task.id()));
        } finally {
            jdbc.execute("alter table custody_webhook_delivery_attempt_unavailable rename to custody_webhook_delivery_attempt");
        }
        assertEquals("IN_PROGRESS", jdbc.queryForObject(
                "select status from custody_webhook_delivery_attempt where id = ?", String.class, task.attemptId()));
    }
    @Test
    void claimReceiptFailureRollsBackTheLeaseAndBothCounters() {
        jdbc.execute("alter table custody_webhook_delivery_attempt rename to custody_webhook_delivery_attempt_unavailable");
        try {
            assertThrows(RuntimeException.class, () -> webhooks.claimWebhookDeliveries("worker", 1));
            assertEquals("PENDING", jdbc.queryForObject("select status from custody_webhook_delivery", String.class));
            assertEquals(0, jdbc.queryForObject("select total_attempt_count from custody_webhook_delivery", Integer.class));
            assertEquals(0, jdbc.queryForObject("select attempt_count from custody_webhook_delivery", Integer.class));
            assertNull(jdbc.queryForObject("select locked_by from custody_webhook_delivery", String.class));
        } finally {
            jdbc.execute("alter table custody_webhook_delivery_attempt_unavailable rename to custody_webhook_delivery_attempt");
        }
        assertEquals(1, webhooks.claimWebhookDeliveries("worker", 1).getFirst().totalAttemptCount());
    }

    @Test
    void failedReceiptWriteRollsBackTheRetryState() {
        var task = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        jdbc.execute("alter table custody_webhook_delivery_attempt rename to custody_webhook_delivery_attempt_unavailable");
        try {
            assertThrows(RuntimeException.class, () -> webhooks.markWebhookFailed(task, 503, "retry", "", Instant.now().plusSeconds(30), false, 1));
            assertEquals("DELIVERING", deliveryStatus(task.id()));
        } finally {
            jdbc.execute("alter table custody_webhook_delivery_attempt_unavailable rename to custody_webhook_delivery_attempt");
        }
        assertEquals("IN_PROGRESS", attemptStatus(task.attemptId()));
    }

    @Test
    void endpointTimestampFailureRollsBackDeliveryAndReceipt() {
        var task = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        jdbc.execute("alter table custody_webhook_endpoint rename to custody_webhook_endpoint_unavailable");
        try {
            assertThrows(RuntimeException.class, () -> webhooks.markWebhookDelivered(task, 200, "ok", 1));
            assertEquals("DELIVERING", deliveryStatus(task.id()));
            assertEquals("IN_PROGRESS", attemptStatus(task.attemptId()));
        } finally {
            jdbc.execute("alter table custody_webhook_endpoint_unavailable rename to custody_webhook_endpoint");
        }
    }

    @Test
    void aMismatchedReceiptCannotCommitTheDelivery() {
        var task = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        var mismatched = new WebhookDeliveryTask(task.id(), task.tenantId(), task.endpointId(), task.eventId(),
                task.attemptCount(), task.totalAttemptCount(), task.manualRetryCount(), task.attemptTrigger(),
                UUID.randomUUID(), task.workerId(), task.url(), task.secretCiphertext(), task.eventType(), task.payload());
        assertThrows(IllegalStateException.class, () -> webhooks.markWebhookDelivered(mismatched, 200, "ok", 1));
        assertEquals("DELIVERING", deliveryStatus(task.id()));
        assertEquals("IN_PROGRESS", attemptStatus(task.attemptId()));
    }

    @Test
    void aStaleFailureCannotOverwriteASameWorkerRecovery() {
        var stale = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        jdbc.update("update custody_webhook_delivery set locked_at = now() - interval '6 minutes' where id = ?", stale.id());
        var recovered = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        assertEquals(0, context.getBean(CustodyWebhookDeliveryRepository.class).delivered(stale.tenantId(), stale.id(),
                stale.endpointId(), stale.eventId(), stale.workerId(), stale.totalAttemptCount(), 200, "late"));
        assertFalse(webhooks.markWebhookFailed(stale, 500, "late", "", null, true, 1));
        assertEquals("DELIVERING", deliveryStatus(recovered.id()));
        assertEquals("IN_PROGRESS", attemptStatus(recovered.attemptId()));
        assertTrue(webhooks.markWebhookFailed(recovered, 503, "retry", "", Instant.now().plusSeconds(30), false, 1));
        makeDue(recovered.id());
        assertEquals("AUTOMATIC", webhooks.claimWebhookDeliveries("worker", 1).getFirst().attemptTrigger());
    }

    @Test
    void tenFailuresAreTerminalAndManualRetryStartsANewCycleWithoutLosingHistory() {
        var policy = new CustodyWebhookRetryPolicy();
        WebhookDeliveryTask last = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            last = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
            assertEquals(attempt, last.attemptCount());
            assertEquals(attempt, last.totalAttemptCount());
            var decision = policy.decide(last.id(), last.attemptCount(), null, Instant.now());
            assertEquals(attempt == 10, decision.terminal());
            webhooks.markWebhookFailed(last, 503, "retry", "", decision.nextAttemptAt(), decision.terminal(), 1);
            if (!decision.terminal()) makeDue(last.id());
        }
        assertEquals("FAILED", deliveryStatus(last.id()));
        assertTrue(webhooks.claimWebhookDeliveries("worker", 1).isEmpty());
        webhooks.retry(admin(), last.id(), "127.0.0.1");
        var manual = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        assertEquals(1, manual.attemptCount());
        assertEquals(11, manual.totalAttemptCount());
        assertEquals(1, manual.manualRetryCount());
        assertEquals("MANUAL", manual.attemptTrigger());
        var decision = policy.decide(manual.id(), manual.attemptCount(), null, Instant.now());
        assertFalse(decision.terminal());
        webhooks.markWebhookFailed(manual, 503, "retry", "", decision.nextAttemptAt(), false, 1);
        var history = webhooks.deliveryAttempts(admin(), manual.id(), 20, 0);
        assertEquals(11, history.size());
        assertEquals(11, history.getFirst().attemptNumber());
        assertEquals(1, history.getFirst().retryCycle());
        assertEquals("MANUAL", history.getFirst().trigger());
    }

    @Test
    void concurrentClaimsCreateOnlyOneCurrentReceipt() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 16).mapToObj(i -> executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return webhooks.claimWebhookDeliveries("worker-" + i, 1).size();
            })).toList();
            start.countDown();
            int claimed = 0;
            for (var future : futures) claimed += future.get(20, TimeUnit.SECONDS);
            assertEquals(1, claimed);
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_webhook_delivery_attempt", Integer.class));
        assertEquals(1, jdbc.queryForObject("select total_attempt_count from custody_webhook_delivery", Integer.class));
    }

    @Test
    void publicViewsExcludeCredentialsAndCreationPersistsItsTenantActor() throws Exception {
        var created = webhooks.create(admin(), new CustodyWebhookService.CreateWebhookCommand("Created", "http://127.0.0.1:1/new"), "127.0.0.1");
        assertEquals(USER, jdbc.queryForObject("select created_by from custody_webhook_endpoint where id = ?", UUID.class, created.id()));
        var list = webhooks.list(admin());
        String json = mapper.writeValueAsString(list);
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("verification"));
        assertTrue(json.contains("createdAt"));
        assertFalse(json.contains(created.signingSecret()));
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'custody_webhook_endpoint' and column_name = 'verification_token_hash'", Integer.class));
        var task = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        var delivery = webhooks.deliveries(admin(), endpointId, "DELIVERING", 10, 0).getFirst();
        assertEquals(task.eventId(), delivery.eventId());
        assertEquals("DEPOSIT.CONFIRMED", delivery.eventType());
        assertEquals("DEPOSIT", delivery.aggregateType());
        assertEquals("lifecycle-deposit", delivery.aggregateId());
        assertEquals(1, delivery.attemptCount());
        assertNotNull(delivery.nextAttemptAt());
        assertTrue(webhooks.deliveriesForTenant(UUID.randomUUID(), endpointId, null, 10, 0).isEmpty());
    }

    @Test
    void endpointCreationRejectsACreatorFromAnotherTenant() {
        UUID foreignTenant = UUID.randomUUID();
        context.getBean(CustodyTenantTableRepository.class).insert(foreignTenant, "foreign-" + foreignTenant, "Foreign tenant");
        var mismatched = new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER, USER, foreignTenant,
                "foreign", "TENANT_ADMIN", Set.of("*"));
        assertThrows(RuntimeException.class, () -> webhooks.create(mismatched,
                new CustodyWebhookService.CreateWebhookCommand("Foreign", "http://127.0.0.1:1/hook"), "127.0.0.1"));
        assertTrue(context.getBean(CustodyWebhookEndpointRepository.class).list(foreignTenant).isEmpty());
        assertEquals(0, context.getBean(CustodyAuditRepository.class).count(foreignTenant));
    }

    @Test
    void verificationHttpRunsOutsideACallerTransactionThenCommitsActivationAndAudit() throws Exception {
        try (var server = new EchoServer()) {
            var endpoint = webhooks.create(admin(), new CustodyWebhookService.CreateWebhookCommand("Verify", server.url()), "127.0.0.1");
            transactions.executeWithoutResult(status -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("ACTIVE", webhooks.verify(admin(), endpoint.id(), "127.0.0.1").status());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                status.setRollbackOnly();
            });
            assertEquals(List.of(false), transport.transactionObservations);
            assertEquals("ACTIVE", context.getBean(CustodyWebhookEndpointRepository.class).find(TENANT, endpoint.id()).orElseThrow().status());
            assertEquals(1, jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id = ? and action = 'WEBHOOK.VERIFY'", Integer.class, TENANT));
        }
    }

    @Test
    void verificationAuditFailureRollsBackActivation() throws Exception {
        try (var server = new EchoServer()) {
            var endpoint = webhooks.create(admin(), new CustodyWebhookService.CreateWebhookCommand("Verify", server.url()), "127.0.0.1");
            withoutAudit(() -> assertThrows(RuntimeException.class, () -> webhooks.verify(admin(), endpoint.id(), "127.0.0.1")));
            assertEquals("PENDING_VERIFICATION", context.getBean(CustodyWebhookEndpointRepository.class).find(TENANT, endpoint.id()).orElseThrow().status());
            assertEquals(List.of(false), transport.transactionObservations);
        }
    }

    @Test
    void endpointDisabledDuringHttpVerificationCannotBeActivatedByAnOldResponse() throws Exception {
        try (var server = new EchoServer()) {
            var endpoint = webhooks.create(admin(), new CustodyWebhookService.CreateWebhookCommand("Verify", server.url()), "127.0.0.1");
            transport.beforeSend = () -> context.getBean(CustodyWebhookEndpointRepository.class).updateStatus(TENANT, endpoint.id(), "DISABLED");
            assertThrows(IllegalStateException.class, () -> webhooks.verify(admin(), endpoint.id(), "127.0.0.1"));
            assertEquals("DISABLED", context.getBean(CustodyWebhookEndpointRepository.class).find(TENANT, endpoint.id()).orElseThrow().status());
            assertEquals(0, jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id = ? and action = 'WEBHOOK.VERIFY'", Integer.class, TENANT));
        }
    }

    @Test
    void creatingAndRetryingShareTheirAuditTransactions() {
        int endpointsBefore = context.getBean(CustodyWebhookEndpointRepository.class).list(TENANT).size();
        withoutAudit(() -> assertThrows(RuntimeException.class,
                () -> webhooks.create(admin(), new CustodyWebhookService.CreateWebhookCommand("Created", "http://127.0.0.1:1/new"), "127.0.0.1")));
        assertEquals(endpointsBefore, context.getBean(CustodyWebhookEndpointRepository.class).list(TENANT).size());
        var task = webhooks.claimWebhookDeliveries("worker", 1).getFirst();
        webhooks.markWebhookFailed(task, 500, "terminal", "", null, true, 1);
        withoutAudit(() -> assertThrows(RuntimeException.class, () -> webhooks.retry(admin(), task.id(), "127.0.0.1")));
        assertEquals("FAILED", deliveryStatus(task.id()));
        assertEquals(0, jdbc.queryForObject("select manual_retry_count from custody_webhook_delivery where id = ?", Integer.class, task.id()));
    }

    @Test
    void dispatchCommitsItsClaimBeforeSendingHttpOutsideTheCallerTransaction() throws Exception {
        try (var server = new EchoServer()) {
            jdbc.update("update custody_webhook_endpoint set url = ? where id = ?", server.url(), endpointId);
            transport.beforeSend = () -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(1, jdbc.queryForObject("select count(*) from custody_webhook_delivery_attempt where status = 'IN_PROGRESS'", Integer.class));
            };
            transactions.executeWithoutResult(status -> {
                context.getBean(CustodyWebhookDispatchService.class).dispatch();
                status.setRollbackOnly();
            });
            assertEquals(List.of(false), transport.transactionObservations);
            assertEquals("DELIVERED", jdbc.queryForObject("select status from custody_webhook_delivery", String.class));
            assertEquals("DELIVERED", jdbc.queryForObject("select status from custody_webhook_delivery_attempt", String.class));
        }
    }

    private CustodyPrincipal admin() {
        return new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER, USER, TENANT, "test-tenant", "TENANT_ADMIN", Set.of("*"));
    }

    private void makeDue(UUID id) { jdbc.update("update custody_webhook_delivery set next_attempt_at = now() where id = ?", id); }
    private String deliveryStatus(UUID id) { return jdbc.queryForObject("select status from custody_webhook_delivery where id = ?", String.class, id); }
    private String attemptStatus(UUID id) { return jdbc.queryForObject("select status from custody_webhook_delivery_attempt where id = ?", String.class, id); }
    private void withoutAudit(Runnable action) {
        jdbc.execute("alter table custody_audit_log rename to custody_audit_log_unavailable");
        try { action.run(); } finally { jdbc.execute("alter table custody_audit_log_unavailable rename to custody_audit_log"); }
    }

    private static class RecordingHttpClient extends CustodyWebhookHttpClient {
        private final List<Boolean> transactionObservations = new CopyOnWriteArrayList<>();
        private Runnable beforeSend = () -> { };
        private RecordingHttpClient(CustodyCryptoService crypto) { super(crypto, "dev"); }
        @Override public WebhookHttpResult send(String url, String secret, UUID eventId, String eventType, String body) {
            transactionObservations.add(TransactionSynchronizationManager.isActualTransactionActive());
            beforeSend.run();
            return super.send(url, secret, eventId, eventType, body);
        }
    }

    private final class EchoServer implements AutoCloseable {
        private final HttpServer server;
        private EchoServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                try (exchange) {
                    var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
                    String challenge = request.path("data").path("challenge").asText("");
                    byte[] response = mapper.writeValueAsBytes(Map.of("challenge", challenge));
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
        }
        private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook"; }
        @Override public void close() { server.stop(0); }
    }

}
