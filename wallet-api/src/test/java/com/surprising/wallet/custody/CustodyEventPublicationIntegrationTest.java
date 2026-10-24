package com.surprising.wallet.custody;

import com.surprising.wallet.repository.CustodyEventRepository;
import com.surprising.wallet.repository.CustodyWebhookDeliveryRepository;
import com.surprising.wallet.repository.CustodyWebhookEndpointRepository;
import com.surprising.wallet.service.CustodyEventService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyEventPublicationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private CustodyEventService events;
    private CustodyWebhookEndpointRepository endpoints;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(CustodyEventRepository.class);
        context.registerBean(CustodyWebhookEndpointRepository.class);
        context.registerBean(CustodyWebhookDeliveryRepository.class);
        context.registerBean(CustodyEventService.class);
        context.refresh();
        events = context.getBean(CustodyEventService.class);
        endpoints = context.getBean(CustodyWebhookEndpointRepository.class);
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void apiPublishesOnlyToActiveTenantEndpointsWhileConsoleStillRecordsItsEvent() {
        UUID active = endpoint(true);
        endpoint(false);
        UUID apiEvent = publish("API-SOURCE", true);
        UUID consoleEvent = publish("CONSOLE-SOURCE", false);
        assertEquals(2, count("custody_event"));
        assertEquals(1, count("custody_webhook_delivery"));
        assertEquals(active, jdbc.queryForObject("select endpoint_id from custody_webhook_delivery", UUID.class));
        assertEquals(apiEvent, jdbc.queryForObject("select event_id from custody_webhook_delivery", UUID.class));
        assertEquals("PUBLISHED", jdbc.queryForObject("select status from custody_event where id=?", String.class, consoleEvent));
    }

    @Test void replayReusesTheBusinessEventAndItsDelivery() {
        endpoint(true);
        UUID first = publish("REPLAY", true);
        assertEquals(first, publish("REPLAY", true));
        assertEquals(1, count("custody_event"));
        assertEquals(1, count("custody_webhook_delivery"));
    }

    @Test void concurrentReplaysCreateOneEventAndOneDelivery() throws Exception {
        endpoint(true);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 24).mapToObj(i -> executor.submit(() -> publish("CONCURRENT", true))).toList();
            UUID first = futures.getFirst().get(20, TimeUnit.SECONDS);
            for (var future : futures) assertEquals(first, future.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, count("custody_event"));
        assertEquals(1, count("custody_webhook_delivery"));
    }

    @Test void deliveryFailureRollsBackTheEventWithoutAnOuterTransactionAndCanRetry() {
        endpoint(true);
        jdbc.execute("alter table custody_webhook_delivery rename to custody_webhook_delivery_unavailable");
        try {
            assertThrows(RuntimeException.class, () -> publish("ROLLBACK", true));
            assertEquals(0, count("custody_event"));
        } finally {
            jdbc.execute("alter table custody_webhook_delivery_unavailable rename to custody_webhook_delivery");
        }
        publish("ROLLBACK", true);
        assertEquals(1, count("custody_event"));
        assertEquals(1, count("custody_webhook_delivery"));
    }

    private UUID endpoint(boolean active) {
        UUID id = UUID.randomUUID();
        endpoints.insert(id, TENANT, "test-" + id, "https://example.invalid/hook/" + id, "test-only", UUID.fromString("00000000-0000-0000-0000-000000000002"));
        if (active) assertEquals(1, endpoints.markVerified(TENANT, id));
        return id;
    }

    private UUID publish(String businessId, boolean apiSource) {
        return events.publish(UUID.randomUUID(), TENANT, "DEPOSIT.CONFIRMED", "DEPOSIT", businessId, "{}", apiSource);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
