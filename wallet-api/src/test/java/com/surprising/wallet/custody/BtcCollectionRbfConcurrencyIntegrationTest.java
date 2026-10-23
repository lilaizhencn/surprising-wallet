package com.surprising.wallet.custody;

import com.surprising.wallet.repository.ChainFeeRateRepository;
import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.signing.*;
import static com.surprising.wallet.testutil.UtxoSigningTestSupport.*;
import com.surprising.wallet.common.queue.*;
import com.surprising.wallet.common.utils.Constants;
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
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PG18 row locks, queue transactions and settlement; no extra database server. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class BtcCollectionRbfConcurrencyIntegrationTest {
    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String ORIGINAL = "a".repeat(64), REPLACEMENT = "b".repeat(64), INPUT = "c".repeat(64);
    static final AssetRuntimeMetadata BTC = AssetRuntimeMetadata.fromProfile(1, "BTC", "BTC", 1, 0, 8, null);
    final ObjectMapper json = new ObjectMapper();
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    ChainJdbcRepository repository;
    RbfBumpService rbf;
    BitcoinLikeSettlementService settlement;
    UtxoBroadcastService broadcaster;
    ChainSigningTransactionRepository signing;
    BlockchainRuntimeService runtime;
    PgmqClient queue;
    ChainFeeRateRepository rates;
    int id;
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        repository = com.surprising.wallet.testutil.DepositTestSupport.repository(jdbc);
        runtime = mock(BlockchainRuntimeService.class);
        rates = mock(ChainFeeRateRepository.class);
        var switches = mock(WalletRuntimeConfigService.class);
        when(runtime.assetMetadata("BTC")).thenReturn(BTC);
        when(runtime.isBitcoinLikeRuntime(any(AssetRuntimeMetadata.class))).thenReturn(true);
        when(runtime.chainName(any(AssetRuntimeMetadata.class))).thenReturn("BTC");
        when(runtime.broadcastSignedTransaction(any(), any())).thenReturn(REPLACEMENT);
        when(switches.isTaskEnabled(eq("BTC"), anyString())).thenReturn(true);
        when(rates.find("btc")).thenReturn("10");
        queue = new PgmqClient(jdbc);
        var manager = new DataSourceTransactionManager(ds);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.registerBean(ChainJdbcRepository.class, () -> repository);
        signing = new ChainSigningTransactionRepository(jdbc, json);
        context.registerBean(ChainSigningTransactionRepository.class, () -> signing);
        context.registerBean(WithdrawalOrderRepository.class, () -> new WithdrawalOrderRepository(jdbc));
        context.registerBean(CollectionRecordRepository.class, () -> new CollectionRecordRepository(jdbc));
        context.registerBean(UtxoRepository.class, () -> new UtxoRepository(jdbc));
        context.registerBean(LedgerBalanceRepository.class, () -> new LedgerBalanceRepository(jdbc));
        context.registerBean(BlockchainRuntimeService.class, () -> runtime);
        context.registerBean(WalletRuntimeConfigService.class, () -> switches);
        context.registerBean(ChainFeeRateRepository.class, () -> rates);
        context.registerBean(QueueWorker.class, () -> new QueueWorker(queue, manager));
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(PgmqClient.class, () -> queue);
        context.registerBean(WalletTaskLeaseService.class, () -> {
            var lease = mock(WalletTaskLeaseService.class); when(lease.ownerId()).thenReturn("test-rbf"); return lease;
        });
        context.registerBean(RbfBumpService.class);
        context.registerBean(WithdrawalSettlementService.class,
                () -> com.surprising.wallet.testutil.CustodyTestSupport.settlements(jdbc));
        context.registerBean(BitcoinLikeSettlementService.class);
        context.registerBean(UtxoBroadcastService.class);
        context.refresh();
        rbf = context.getBean(RbfBumpService.class);
        settlement = context.getBean(BitcoinLikeSettlementService.class);
        broadcaster = context.getBean(UtxoBroadcastService.class);
        Long addressId = jdbc.queryForObject("""
                insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, owner_address, derivation_path, wallet_role, enabled)
                values (?, 'BTC', 'BTC', 'test-user', 100, 0, 0, 'source', 'source',
                    'test-derivation', 'DEPOSIT', true) returning id
                """, Long.class, TENANT);
        UUID custodyAddress = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, derivation_subject, derivation_child)
                values (?, ?, ?, 'BTC', 'regtest', 'source', 'test-user', 'API', 100, 0)
                """, custodyAddress, TENANT, addressId);
        repository.createCollectionRecord(TENANT, custodyAddress, "COLL-RBF", "BTC", "BTC", "source", "collector",
                new BigDecimal("0.00998410"), new BigDecimal("0.00001590"), null);
        repository.claimCollectionSigning(TENANT, "BTC", "COLL-RBF", null);
        repository.updateCollectionStatus(TENANT, "BTC", "COLL-RBF", "SENT", ORIGINAL, null, null);
        var request = request(TENANT, UtxoSigningRequest.Operation.COLLECTION, "0.01", "0.00998410");
        var transaction = signing.create(request, "COLL-RBF");
        id = transaction.id();
        signing.update(transaction.withSigned(com.surprising.wallet.testutil.UtxoSigningTestSupport.signed(ORIGINAL, 1590), Constants.SENT));
        repository.upsertUtxo("BTC", "BTC", INPUT, 0, "source", new BigDecimal("0.01"), 1, "block", 6, true);
        assertEquals(1, repository.lockUtxo("BTC", INPUT, 0, String.valueOf(id)));
        jdbc.update("insert into ledger_balance(tenant_id, chain, asset_symbol, account_id, available_balance, total_balance) "
                + "values (?, 'BTC', 'BTC', 'test-user', 0.01, 0.01)", TENANT);
    }
    @AfterEach void close() { if (context != null) context.close(); }
    @Test void duplicateRequestsEnqueueOneReplacementAndKeepInputsLocked() throws Exception {
        for (int i = 0; i < 24; i++) queue.send(WalletQueue.RBF,
                "{\"transactionId\":" + id + ",\"expectedTxId\":\"" + ORIGINAL + "\"}", QueueTenant.headers(TENANT));
        parallel(12, () -> rbf.process());
        assertEquals(1, jdbc.queryForObject("select count(*) from pgmq.q_wallet_sign_first", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pgmq.q_wallet_rbf", Integer.class));
        assertEquals(1, current().attempts().size());
        assertEquals(20, current().request().feeRate());
        assertEquals("LOCKED", inputState());
        assertAmounts("0.00996820", "0.00003180"); assertLedger();
    }
    @Test void concurrentBroadcastAndFinalizationSettleOnlyOnce() throws Exception {
        var body = rbf.bumpFee(id, TENANT, ORIGINAL);
        var signed = signed(body, true);
        parallel(12, () -> broadcaster.apply(signed));
        verify(runtime, times(1)).broadcastSignedTransaction(any(), any());
        assertNull(rbf.bumpFee(id, TENANT, ORIGINAL)); // replay after replacement was broadcast
        parallel(16, () -> settlement.settleConfirmed("BTC", id, REPLACEMENT));
        assertEquals("CONFIRMED", collectionStatus()); assertEquals("SPENT", inputState());
        assertEquals(REPLACEMENT, current().txId());
        settlement.markConfirming("BTC", id, ORIGINAL);
        assertEquals("CONFIRMED", collectionStatus());
        assertAmounts("0.00996820", "0.00003180"); assertLedger();
    }
    @Test void originalConfirmationWinsWhileReplacementIsSigning() throws Exception {
        rbf.bumpFee(id, TENANT, ORIGINAL);
        assertEquals(1, signing.listPendingConfirmation("BTC").size());
        assertTrue(signing.findByTxId("BTC", ORIGINAL).isPresent());
        parallel(16, () -> settlement.settleConfirmed("BTC", id, ORIGINAL));
        assertEquals(ORIGINAL, current().txId()); assertEquals("SPENT", inputState());
        assertAmounts("0.00998410", "0.00001590"); assertLedger();
    }
    @Test void originalConfirmationRestoresAmountsAfterReplacementBroadcast() {
        broadcaster.apply(signed(rbf.bumpFee(id, TENANT, ORIGINAL), true));
        assertTrue(repository.isInternalCollectionTransfer(TENANT, "BTC", ORIGINAL, "collector"));
        assertFalse(repository.isInternalCollectionTransfer(UUID.randomUUID(), "BTC", ORIGINAL, "collector"));
        assertFalse(repository.isInternalCollectionTransfer(TENANT, "BTC", ORIGINAL, "external"));
        settlement.settleConfirmed("BTC", id, ORIGINAL);
        assertAmounts("0.00998410", "0.00001590");
        assertEquals(3180, current().attempts().get(1).transaction().feeAtomic());
        assertEquals(REPLACEMENT, current().attempts().get(1).transaction().txId()); assertLedger();
    }
    @Test void concurrentFeeBumpAndOriginalSettlementCannotReopenConfirmedState() throws Exception {
        parallel(16, () -> {
            if (ThreadLocalRandom.current().nextBoolean()) rbf.bumpFee(id, TENANT, ORIGINAL);
            else settlement.settleConfirmed("BTC", id, ORIGINAL);
        });
        settlement.settleConfirmed("BTC", id, ORIGINAL);
        assertNull(rbf.bumpFee(id, TENANT, ORIGINAL));
        assertEquals(Constants.CONFIRM, current().status()); assertEquals("CONFIRMED", collectionStatus());
        assertEquals("SPENT", inputState()); assertAmounts("0.00998410", "0.00001590"); assertLedger();
    }
    @Test void replacementSigningFailureRetainsInputsUntilOriginalConfirms() {
        var failed = signed(rbf.bumpFee(id, TENANT, ORIGINAL), false);
        assertFalse(json.valueToTree(failed).has("attempts")); // history has no slot in a signer result
        broadcaster.apply(failed);
        assertEquals("LOCKED", inputState()); assertEquals("BROADCAST_UNKNOWN", collectionStatus());
        assertEquals(1, signing.listPendingConfirmation("BTC").size());
        settlement.settleConfirmed("BTC", id, ORIGINAL);
        assertEquals("SPENT", inputState()); assertLedger();
    }
    @Test void unknownReplacementBroadcastIsTrackedAndCanConfirm() {
        when(runtime.broadcastSignedTransaction(any(), any())).thenReturn("");
        broadcaster.apply(signed(rbf.bumpFee(id, TENANT, ORIGINAL), true));
        assertEquals(REPLACEMENT, current().txId()); assertEquals("LOCKED", inputState());
        assertEquals("BROADCAST_UNKNOWN", collectionStatus());
        assertEquals(1, signing.listPendingConfirmation("BTC").size());
        settlement.settleConfirmed("BTC", id, REPLACEMENT);
        assertEquals("CONFIRMED", collectionStatus()); assertAmounts("0.00996820", "0.00003180"); assertLedger();
    }
    @Test void obsoleteSignatureAfterOriginalConfirmationIsAcknowledgedWithoutBroadcast() {
        var signed = signed(rbf.bumpFee(id, TENANT, ORIGINAL), true);
        settlement.settleConfirmed("BTC", id, ORIGINAL);
        broadcaster.apply(signed);
        verify(runtime, never()).broadcastSignedTransaction(any(), any());
        assertEquals("CONFIRMED", collectionStatus()); assertAmounts("0.00998410", "0.00001590"); assertLedger();
    }
    @Test void attemptLimitAndRelativeFeeCapDoNotChangeAmounts() {
        var tx = current();
        var history = new ArrayList<UtxoSigningTransaction.Attempt>();
        for (int index = 0; index < 5; index++) history.add(new UtxoSigningTransaction.Attempt(tx.request(), tx.signed(), java.time.Instant.now()));
        signing.update(new UtxoSigningTransaction(id, tx.request(), tx.signed(), history, tx.status(),
                tx.enqueuedRequestId(), null, tx.createdAt(), tx.updatedAt()));
        assertThrows(IllegalArgumentException.class, () -> rbf.bumpFee(id, TENANT, ORIGINAL));
        var smaller = request(TENANT, UtxoSigningRequest.Operation.COLLECTION, "0.001", "0.00998410");
        signing.update(new UtxoSigningTransaction(id, smaller, tx.signed(), List.of(), tx.status(),
                tx.enqueuedRequestId(), null, tx.createdAt(), tx.updatedAt()));
        when(rates.find("btc")).thenReturn("70"); // 11130 sat > 10% of this input, below absolute limit
        assertThrows(IllegalArgumentException.class, () -> rbf.bumpFee(id, TENANT, ORIGINAL));
        assertEquals(ORIGINAL, current().txId()); assertAmounts("0.00998410", "0.00001590"); assertLedger();
    }
    @Test void settlementInputMismatchRollsBackTerminalStatusAndAmounts() {
        rbf.bumpFee(id, TENANT, ORIGINAL);
        jdbc.update("update utxo_record set lock_ref = 'other' where tx_hash = ?", INPUT);
        assertThrows(IllegalStateException.class, () -> settlement.settleConfirmed("BTC", id, ORIGINAL));
        assertEquals(Constants.SIGNING, current().status()); assertEquals("SIGNING", collectionStatus());
        assertAmounts("0.00996820", "0.00003180"); assertLedger();
    }
    @Test void unavailableNextQueueRollsBackReplacementAndAmounts() {
        queue.send(WalletQueue.RBF, "{\"transactionId\":" + id + ",\"expectedTxId\":\"" + ORIGINAL + "\"}", QueueTenant.headers(TENANT));
        jdbc.execute("alter table pgmq.q_wallet_sign_first rename to q_wallet_sign_first_unavailable");
        try { rbf.process(); }
        finally { jdbc.execute("alter table pgmq.q_wallet_sign_first_unavailable rename to q_wallet_sign_first"); }
        assertEquals(ORIGINAL, current().txId()); assertTrue(current().attempts().isEmpty());
        assertEquals("LOCKED", inputState()); assertAmounts("0.00998410", "0.00001590"); assertLedger();
    }
    @Test void tenantInputLockAndFeeLimitsRollbackWithoutMutation() {
        assertThrows(IllegalArgumentException.class, () -> rbf.bumpFee(id, UUID.randomUUID(), ORIGINAL));
        assertThrows(IllegalArgumentException.class, () -> rbf.bumpFee(id, TENANT, ""));
        when(rates.find("btc")).thenReturn("1001");
        assertThrows(IllegalArgumentException.class, () -> rbf.bumpFee(id, TENANT, ORIGINAL));
        when(rates.find("btc")).thenReturn("1000");
        assertThrows(IllegalArgumentException.class, () -> rbf.bumpFee(id, TENANT, ORIGINAL));
        when(rates.find("btc")).thenReturn("10");
        jdbc.update("update utxo_record set lock_ref = 'other' where tx_hash = ?", INPUT);
        assertThrows(IllegalStateException.class, () -> rbf.bumpFee(id, TENANT, ORIGINAL));
        assertEquals(ORIGINAL, current().txId()); assertTrue(current().attempts().isEmpty()); assertLedger();
    }
    private UtxoSigningResult signed(UtxoSigningTask task, boolean valid) {
        return valid ? UtxoSigningResult.success(task, com.surprising.wallet.testutil.UtxoSigningTestSupport.signed(REPLACEMENT,
                task.request().inputAtomic() - task.request().paymentAtomic()))
                : UtxoSigningResult.failure(task, "test failure");
    }
    private UtxoSigningTransaction current() { return signing.findById("BTC", id).orElseThrow(); }
    private String inputState() { return jdbc.queryForObject("select state from utxo_record where tx_hash = ?", String.class, INPUT); }
    private String collectionStatus() { return repository.findCollectionStatus(TENANT, "BTC", "COLL-RBF").orElseThrow(); }
    private void assertAmounts(String amount, String fee) {
        var row = jdbc.queryForMap("select amount, fee from collection_record where collection_no = 'COLL-RBF'");
        assertEquals(0, new BigDecimal(amount).compareTo((BigDecimal) row.get("amount")));
        assertEquals(0, new BigDecimal(fee).compareTo((BigDecimal) row.get("fee")));
        assertEquals(0, new BigDecimal("0.01").compareTo(((BigDecimal) row.get("amount")).add((BigDecimal) row.get("fee"))));
    }
    private void assertLedger() {
        var row = jdbc.queryForMap("select available_balance, locked_balance, total_balance from ledger_balance where account_id = 'test-user'");
        assertEquals(0, new BigDecimal("0.01").compareTo((BigDecimal) row.get("available_balance")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get("locked_balance")));
        assertEquals(0, new BigDecimal("0.01").compareTo((BigDecimal) row.get("total_balance")));
        assertEquals(0, jdbc.queryForObject("select count(*) from withdrawal_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from deposit_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pgmq.q_wallet_withdraw_event", Integer.class));
    }
    private void parallel(int count, Runnable task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < count; i++) tasks.add(pool.submit(() -> {
                try { assertTrue(start.await(10, TimeUnit.SECONDS)); task.run(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            start.countDown();
            for (var future : tasks) future.get(30, TimeUnit.SECONDS);
        }
    }
}
