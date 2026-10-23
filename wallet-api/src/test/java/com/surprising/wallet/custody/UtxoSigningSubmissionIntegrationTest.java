package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainFeeRateRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import com.surprising.wallet.service.UtxoBatchService;
import com.surprising.wallet.service.UtxoSigningSubmissionService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import com.surprising.wallet.testutil.DepositTestSupport;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.*;
import static com.surprising.wallet.testutil.UtxoSigningTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG18 transactions cover initial signing without a second delivery store. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class UtxoSigningSubmissionIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AssetRuntimeMetadata BTC = AssetRuntimeMetadata.fromProfile(1, "BTC", "BTC", 1, 0, 8, null);
    private final ObjectMapper json = new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ChainRepositoryFixture repository;
    private ChainSigningTransactionRepository transactions;
    private UtxoSigningSubmissionService submissions;
    private PgmqClient queue;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("truncate pgmq.q_wallet_sign_first, pgmq.a_wallet_sign_first");
        repository = DepositTestSupport.repository(jdbc);
        transactions = new ChainSigningTransactionRepository(jdbc, json);
        queue = new PgmqClient(jdbc);
        var runtime = mock(BlockchainRuntimeService.class);
        when(runtime.assetMetadata("BTC")).thenReturn(BTC);
        when(runtime.depositConfirmationThreshold(BTC)).thenReturn(1L);
        when(runtime.dustThresholdAtomic(BTC)).thenReturn(546L);
        var switches = mock(WalletRuntimeConfigService.class);
        when(switches.isTaskEnabled("BTC", WalletRuntimeConfigService.TASK_WITHDRAW)).thenReturn(true);
        var rates = mock(ChainFeeRateRepository.class);
        when(rates.find("btc")).thenReturn("1");
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(ChainRepositoryFixture.class, () -> repository);
        context.registerBean(ChainSigningTransactionRepository.class, () -> transactions);
        context.registerBean(ChainProfileRepository.class, () -> new ChainProfileRepository(jdbc));
        context.registerBean(BlockchainRuntimeService.class, () -> runtime);
        context.registerBean(WalletRuntimeConfigService.class, () -> switches);
        context.registerBean(ChainFeeRateRepository.class, () -> rates);
        context.registerBean(PgmqClient.class, () -> queue);
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(UtxoSigningSubmissionService.class);
        context.registerBean(UtxoBatchService.class);
        repository.register(context);
        context.refresh();
        submissions = context.getBean(UtxoSigningSubmissionService.class);
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void concurrentDuplicateSubmissionsSurviveArchiveCleanupWithoutResending() throws Exception {
        UUID request = UUID.randomUUID();
        var tx = createSigning(request);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 24).mapToObj(i -> executor.submit(() ->
                    submissions.enqueue(TENANT, "BTC", tx.id(), request))).toList();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, queued());
        var payload = JacksonJson.readObject(json, jdbc.queryForObject(
                "select message::text from pgmq.q_wallet_sign_first", String.class));
        assertEquals("BTC", payload.path("request").path("asset").path("chain").asText());
        assertEquals(0, new BigDecimal("0.01000001").compareTo(payload.path("request").path("inputs").get(0).path("amount").decimalValue()));
        assertEquals(request.toString(), payload.path("request").path("requestId").asText());
        assertEquals(TENANT.toString(), jdbc.queryForObject(
                "select headers->>'tenant_id' from pgmq.q_wallet_sign_first", String.class));
        queue.archive(WalletQueue.SIGN_FIRST, queue.read(WalletQueue.SIGN_FIRST, 30, 1).getFirst());
        jdbc.execute("truncate pgmq.a_wallet_sign_first");
        submissions.enqueue(TENANT, "BTC", tx.id(), request);
        assertEquals(0, queued());
        assertEquals(request, receipt(tx.id()));
    }

    @Test void newRequestCanBeQueuedButOldAndForeignRequestsAreRejected() {
        UUID first = UUID.randomUUID();
        var tx = createSigning(first);
        assertThrows(IllegalArgumentException.class, () -> submissions.enqueue(UUID.randomUUID(), "BTC", tx.id(), first));
        assertNull(receipt(tx.id()));
        submissions.enqueue(TENANT, "BTC", tx.id(), first);
        UUID next = UUID.randomUUID();
        var replacement = tx.request().replacement(next, 10, tx.request().payments());
        assertEquals(1, transactions.update(new UtxoSigningTransaction(tx.id(), replacement, null,
                tx.attempts(), tx.status(), tx.enqueuedRequestId(), null, tx.createdAt(), tx.updatedAt())));
        assertThrows(IllegalArgumentException.class, () -> submissions.enqueue(TENANT, "BTC", tx.id(), first));
        submissions.enqueue(TENANT, "BTC", tx.id(), next);
        submissions.enqueue(TENANT, "BTC", tx.id(), next);
        assertEquals(2, queued());
        assertEquals(next, receipt(tx.id()));
    }

    @Test void terminalTransactionCannotCreateAnInitialSigningTask() {
        UUID request = UUID.randomUUID();
        var tx = createSigning(request);
        transactions.update(tx.withSigned(signed("a".repeat(64), 1590), Constants.CONFIRM));
        assertThrows(IllegalStateException.class, () -> submissions.enqueue(TENANT, "BTC", tx.id(), request));
        assertEquals(0, queued());
        assertNull(receipt(tx.id()));
    }

    @Test void outerRollbackRemovesQueueMessageAndReceiptTogether() {
        UUID request = UUID.randomUUID();
        var tx = createSigning(request);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            submissions.enqueue(TENANT, "BTC", tx.id(), request);
            assertEquals(1, queued());
            status.setRollbackOnly();
        });
        assertEquals(0, queued());
        assertNull(receipt(tx.id()));
        submissions.enqueue(TENANT, "BTC", tx.id(), request);
        assertEquals(1, queued());
    }

    @Test void queueFailureRollsBackOrderInputLocksAndSigningRecordThenRetrySucceeds() {
        seedWithdrawal();
        var batch = context.getBean(UtxoBatchService.class);
        jdbc.execute("alter table pgmq.q_wallet_sign_first rename to q_wallet_sign_first_unavailable");
        try {
            assertThrows(IllegalStateException.class, () -> batch.execute("BTC"));
        } finally {
            jdbc.execute("alter table pgmq.q_wallet_sign_first_unavailable rename to q_wallet_sign_first");
        }
        assertEquals("FROZEN", jdbc.queryForObject("select status from withdrawal_order where order_no='TEST-SUBMIT'", String.class));
        assertEquals("AVAILABLE", jdbc.queryForObject("select state from utxo_record", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chain_signing_transaction", Integer.class));
        assertEquals(0, queued());
        batch.execute("BTC");
        batch.execute("BTC");
        assertEquals("SIGNING", jdbc.queryForObject("select status from withdrawal_order where order_no='TEST-SUBMIT'", String.class));
        assertEquals("LOCKED", jdbc.queryForObject("select state from utxo_record", String.class));
        assertEquals(1, queued());
        assertNotNull(jdbc.queryForObject("select enqueued_signing_request_id from chain_signing_transaction", UUID.class));
    }

    private UtxoSigningTransaction createSigning(UUID requestId) {
        var request = request(TENANT, UtxoSigningRequest.Operation.WITHDRAWAL, "0.01000001", "0.005");
        return transactions.create(request.replacement(requestId, 10, request.payments()), "TEST-REQUEST");
    }

    private int queued() { return jdbc.queryForObject("select count(*) from pgmq.q_wallet_sign_first", Integer.class); }
    private UUID receipt(int id) {
        return jdbc.queryForObject("select enqueued_signing_request_id from chain_signing_transaction where id=?", UUID.class, id);
    }

    private void seedWithdrawal() {
        Long addressId = jdbc.queryForObject("""
                insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, owner_address, derivation_path, wallet_role, enabled)
                values (?, 'BTC', 'BTC', 'hot', 198, 1, 0, 'hot', 'hot', 'test-derivation', 'HOT', true)
                returning id
                """, Long.class, TENANT);
        UUID custody = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, derivation_subject, derivation_child)
                values (?, ?, ?, 'BTC', 'regtest', 'hot', 'hot', 'API', 198, 0)
                """, custody, TENANT, addressId);
        jdbc.update("""
                insert into custody_gas_account(id, tenant_id, custody_address_id, chain, network, native_symbol)
                values (?, ?, ?, 'BTC', 'regtest', 'BTC')
                """, UUID.randomUUID(), TENANT, custody);
        repository.withdrawalOrderRepository.createForTenant(
                TENANT,
                "TEST-SUBMIT",
                100,
                "BTC",
                "BTC",
                "hot",
                "user",
                "destination",
                new BigDecimal("0.001"),
                new BigDecimal("0.00002"));
        repository.withdrawalOrderRepository.updateStatus(TENANT, "BTC", "TEST-SUBMIT", "FROZEN", "hot", null, null);
        repository.utxoRepository.upsert("BTC", "BTC", "c".repeat(64), 0, "hot", new BigDecimal("0.01"), 1, "block", 6, true);
    }
}
