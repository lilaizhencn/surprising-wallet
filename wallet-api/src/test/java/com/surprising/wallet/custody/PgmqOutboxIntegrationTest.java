package com.surprising.wallet.custody;

import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.repository.WalletOutboxRepository;
import com.surprising.wallet.service.WalletOutboxDispatchService;
import com.surprising.wallet.service.WalletTaskLeaseService;
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
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class PgmqOutboxIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private WalletOutboxDispatchService service;
    private UUID id;
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("truncate pgmq.q_wallet_sign_first");
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(WalletOutboxRepository.class);
        context.registerBean(PgmqClient.class);
        context.registerBean(WalletTaskLeaseService.class, () -> {
            var lease = mock(WalletTaskLeaseService.class);
            when(lease.ownerId()).thenReturn("test-outbox"); return lease;
        });
        context.registerBean(WalletOutboxDispatchService.class);
        context.refresh();
        service = context.getBean(WalletOutboxDispatchService.class);
        id = UUID.randomUUID();
        context.getBean(WalletOutboxRepository.class).insert(id,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                WalletOutboxDispatchService.SIGNING_FIRST_TOPIC, "SIGNING", "1", "1", "{}");
    }
    @AfterEach void close() { if (context != null) context.close(); }
    @Test void dispatchCommitsQueueAndOutboxExactlyOnce() {
        service.dispatch(WalletOutboxDispatchService.SIGNING_FIRST_TOPIC, WalletQueue.SIGN_FIRST);
        service.dispatch(WalletOutboxDispatchService.SIGNING_FIRST_TOPIC, WalletQueue.SIGN_FIRST);
        assertEquals("DISPATCHED", status());
        assertEquals(1L, jdbc.queryForObject("select count(*) from pgmq.q_wallet_sign_first", Long.class));
        assertEquals(id.toString(), jdbc.queryForObject("select headers->>'outbox_id' from pgmq.q_wallet_sign_first", String.class));
    }
    @Test void queueFailureRollsBackOutboxClaim() {
        jdbc.execute("alter table pgmq.q_wallet_sign_first rename to q_wallet_sign_first_unavailable");
        try {
            assertThrows(RuntimeException.class, () -> service.dispatch(
                    WalletOutboxDispatchService.SIGNING_FIRST_TOPIC, WalletQueue.SIGN_FIRST));
            assertEquals("PENDING", status());
            assertEquals(0, jdbc.queryForObject("select attempt_count from wallet_outbox where id = ?", Integer.class, id));
        } finally {
            jdbc.execute("alter table pgmq.q_wallet_sign_first_unavailable rename to q_wallet_sign_first");
        }
    }
    private String status() { return jdbc.queryForObject("select status from wallet_outbox where id = ?", String.class, id); }
}
