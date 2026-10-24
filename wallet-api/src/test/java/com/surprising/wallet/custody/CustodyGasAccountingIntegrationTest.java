package com.surprising.wallet.custody;

import com.surprising.wallet.model.custody.GasUsageRecord;
import com.surprising.wallet.repository.CustodyWithdrawalRepository;
import com.surprising.wallet.service.CustodyGasReconciliationService;
import com.surprising.wallet.testutil.CustodyTestSupport;
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

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Gas reservations retain ownership, audit facts and unknown-result locks across retries. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyGasAccountingIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BigDecimal RESERVED = new BigDecimal("0.1");
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private com.surprising.wallet.service.CustodyGasAccountingService accounting;
    private CustodyGasReconciliationService reconciliation;
    private UUID operationId;
    private String gasAccount;
    private String orderNo;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(com.surprising.wallet.service.CustodyGasAccountingService.class, () -> CustodyTestSupport.gasAccounting(jdbc));
        context.registerBean(CustodyGasReconciliationService.class, () -> CustodyTestSupport.gasReconciliation(jdbc));
        context.refresh();
        accounting = context.getBean(com.surprising.wallet.service.CustodyGasAccountingService.class);
        reconciliation = context.getBean(CustodyGasReconciliationService.class);
        gasAccount = "gas-" + UUID.randomUUID();
        UUID gasAddress = address(gasAccount);
        jdbc.update("""
                insert into custody_gas_account(id, tenant_id, custody_address_id, chain, network,
                    native_symbol, low_balance_threshold) values (?, ?, ?, 'ETH', 'qa', 'ETH', 0.1)
                """, UUID.randomUUID(), TENANT, gasAddress);
        jdbc.update("""
                insert into ledger_balance(tenant_id, chain, asset_symbol, account_id,
                    available_balance, locked_balance, total_balance) values (?, 'ETH', 'ETH', ?, 1, 0, 1)
                """, TENANT, gasAccount);
        UUID customerAddress = address("customer-" + UUID.randomUUID());
        orderNo = "gas-order-" + UUID.randomUUID();
        jdbc.update("""
                insert into withdrawal_order(tenant_id, order_no, user_id, chain, asset_symbol,
                    from_address, debit_account_id, to_address, amount, fee, status, tx_hash)
                values (?, ?, 1, 'ETH', 'USDT', 'customer', 'customer', 'destination', 3, 0, 'SENT', 'gas-test-hash')
                """, TENANT, orderNo);
        operationId = UUID.randomUUID();
        new com.surprising.wallet.repository.CustodyWithdrawalRepository(jdbc).insert(operationId, TENANT, customerAddress, new com.surprising.wallet.repository.WithdrawalOrderRepository(jdbc).find("ETH", orderNo, TENANT).orElseThrow().getId().longValue(), orderNo,
                null, null, "ETH", "USDT", "destination", new BigDecimal("3"), BigDecimal.ZERO,
                "SENT", "API_KEY", "gas-test");
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void repeatedReservationReturnsTheOriginalReceiptWithoutAnotherFreeze() {
        var first = reserve();
        assertEquals(first.id(), reserve().id());
        assertBalance("0.9", "0.1", "1");
        assertEquals(1, count("custody_gas_usage"));
    }

    @Test void concurrentReservationsAllReuseOneReceiptAndFreezeOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 24).mapToObj(i -> executor.submit(this::reserve)).toList();
            UUID receipt = futures.getFirst().get(20, TimeUnit.SECONDS).id();
            for (var future : futures) assertEquals(receipt, future.get(20, TimeUnit.SECONDS).id());
        }
        assertBalance("0.9", "0.1", "1");
        assertEquals(1, count("custody_gas_usage"));
    }

    @Test void fundedOverdueFeeIsSelectedAndSettledByReconciliation() {
        reserve();
        assertEquals("OVERDUE", settle("1.2").status());
        assertBalance("0.9", "0.1", "1");
        jdbc.update("""
                update ledger_balance set available_balance=available_balance+0.5,
                    total_balance=total_balance+0.5 where tenant_id=? and account_id=?
                """, TENANT, gasAccount);
        reconciliation.reconcile();
        assertEquals("SETTLED", usageStatus());
        assertBalance("0.3", "0", "0.3");
        assertEquals(1, count("custody_ledger_entry"));
    }

    @Test void expiredReservationForAnUnconfirmedWithdrawalRetainsTheLock() {
        reserve();
        expireReservation();
        reconciliation.reconcile();
        assertEquals("RESERVED", usageStatus());
        assertBalance("0.9", "0.1", "1");
        assertEquals(0, count("custody_ledger_entry"));
    }

    @Test void expiredConfirmedReservationUsesTheObservedNativeFee() {
        reserve();
        expireReservation();
        jdbc.update("update withdrawal_order set status='CONFIRMED' where tenant_id=? and order_no=?", TENANT, orderNo);
        jdbc.update("""
                insert into evm_tx(chain, tx_hash, from_address, to_address, asset_symbol,
                    amount, fee, confirmations, status)
                values ('ETH', 'gas-test-hash', 'customer', 'destination', 'USDT', 3, 0.04, 12, 'CONFIRMED')
                """);
        reconciliation.reconcile();
        assertEquals("SETTLED", usageStatus());
        assertBalance("0.96", "0", "0.96");
        assertEquals(0, new BigDecimal("0.04").compareTo(jdbc.queryForObject(
                "select amount from custody_ledger_entry", BigDecimal.class)));
    }

    @Test void settlementReceiptFailureRollsBackTheBalanceAndReservation() {
        reserve();
        jdbc.execute("alter table custody_ledger_entry rename to unavailable_gas_ledger");
        try { assertThrows(RuntimeException.class, () -> settle("0.04")); }
        finally { jdbc.execute("alter table unavailable_gas_ledger rename to custody_ledger_entry"); }
        assertEquals("RESERVED", usageStatus());
        assertBalance("0.9", "0.1", "1");
        assertEquals("SETTLED", settle("0.04").status());
        assertBalance("0.96", "0", "0.96");
    }

    @Test void releaseAndSettlementReplaysDoNotMoveMoneyAgain() {
        reserve();
        assertEquals("SETTLED", settle("0.04").status());
        assertEquals("SETTLED", settle("0.04").status());
        assertEquals("SETTLED", accounting.release(TENANT, "WITHDRAWAL", operationId, "late release").status());
        assertBalance("0.96", "0", "0.96");
        assertEquals(1, count("custody_ledger_entry"));
    }

    @Test void differentOrTerminalReservationCannotFreezeTheOperationAgain() {
        reserve();
        assertThrows(IllegalStateException.class,
                () -> accounting.reserve(TENANT, "WITHDRAWAL", operationId, new BigDecimal("0.2")));
        assertBalance("0.9", "0.1", "1");
        settle("0.04");
        assertThrows(IllegalStateException.class, this::reserve);
        assertBalance("0.96", "0", "0.96");
    }

    @Test void nonrepresentableAmountsCannotBeRoundedIntoTheLedger() {
        for (String amount : java.util.List.of("0.1000000000000000001", "0.100000000000000000000001",
                "0.10000000000000000000000001", "1e54")) {
            assertThrows(IllegalArgumentException.class,
                    () -> accounting.reserve(TENANT, "WITHDRAWAL", operationId, new BigDecimal(amount)), amount);
        }
        assertBalance("1", "0", "1");
        assertEquals(0, count("custody_gas_usage"));
        reserve();
        for (String amount : java.util.List.of("0.0400000000000000001", "0.04000000000000000000000001", "1e54")) {
            assertThrows(IllegalArgumentException.class, () -> settle(amount), amount);
        }
        assertEquals("RESERVED", usageStatus());
        assertBalance("0.9", "0.1", "1");
    }

    @Test void eighteenFractionDigitsRemainExactInTheBalanceAndReceipt() {
        reserve();
        assertEquals("SETTLED", settle("0.040000000000000001").status());
        assertBalance("0.959999999999999999", "0", "0.959999999999999999");
        assertEquals(0, new BigDecimal("0.040000000000000001").compareTo(jdbc.queryForObject(
                "select amount from custody_ledger_entry", BigDecimal.class)));
    }

    @Test void unchangedExpiredReservationsCannotHideLaterConfirmedFees() {
        UUID customerAddress = jdbc.queryForObject("select custody_address_id from custody_withdrawal where id=?",
                UUID.class, operationId);
        for (int i = 0; i < 101; i++) {
            String pendingOrder = orderNo + "-pending-" + i;
            jdbc.update("""
                    insert into withdrawal_order(tenant_id, order_no, user_id, chain, asset_symbol,
                        from_address, debit_account_id, to_address, amount, fee, status)
                    values (?, ?, 1, 'ETH', 'USDT', 'customer', 'customer', 'destination', 3, 0, 'SENT')
                    """, TENANT, pendingOrder);
            UUID pendingId = UUID.randomUUID();
            new com.surprising.wallet.repository.CustodyWithdrawalRepository(jdbc).insert(pendingId, TENANT, customerAddress, new com.surprising.wallet.repository.WithdrawalOrderRepository(jdbc).find("ETH", pendingOrder, TENANT).orElseThrow().getId().longValue(), pendingOrder,
                    null, null, "ETH", "USDT", "destination", new BigDecimal("3"), BigDecimal.ZERO,
                    "SENT", "API_KEY", "gas-test");
            accounting.reserve(TENANT, "WITHDRAWAL", pendingId, new BigDecimal("0.001"));
        }
        expireReservation();
        reserve();
        jdbc.update("""
                update custody_gas_usage set id='ffffffff-ffff-ffff-ffff-ffffffffffff',
                    created_at=now()-interval '31 minutes' where operation_id=?
                """, operationId);
        jdbc.update("update withdrawal_order set status='CONFIRMED' where tenant_id=? and order_no=?", TENANT, orderNo);
        jdbc.update("""
                insert into evm_tx(chain, tx_hash, from_address, to_address, asset_symbol,
                    amount, fee, confirmations, status)
                values ('ETH', 'gas-test-hash', 'customer', 'destination', 'USDT', 3, 0.04, 12, 'CONFIRMED')
                """);
        reconciliation.reconcile();
        assertEquals("SETTLED", usageStatus());
        assertBalance("0.859", "0.101", "0.96");
        assertEquals(101, jdbc.queryForObject("select count(*) from custody_gas_usage where status='RESERVED'", Integer.class));
        assertEquals(1, count("custody_ledger_entry"));
    }

    @Test void confirmedFeesStillReconcileAfterNewChainAndAssetWorkIsDisabled() {
        reserve();
        expireReservation();
        jdbc.update("update withdrawal_order set status='CONFIRMED' where tenant_id=? and order_no=?", TENANT, orderNo);
        jdbc.update("""
                insert into evm_tx(chain, tx_hash, from_address, to_address, asset_symbol,
                    amount, fee, confirmations, status)
                values ('ETH', 'gas-test-hash', 'customer', 'destination', 'USDT', 3, 0.04, 12, 'CONFIRMED')
                """);
        jdbc.update("update chain_profile set enabled=false where chain='ETH'");
        jdbc.update("update chain_asset set active=false where chain='ETH'");
        reconciliation.reconcile();
        assertEquals("SETTLED", usageStatus());
        assertBalance("0.96", "0", "0.96");
    }

    @Test void reservationParticipatesInTheCallersTransaction() {
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(status -> {
                    reserve();
                    assertBalance("0.9", "0.1", "1");
                    status.setRollbackOnly();
                });
        assertBalance("1", "0", "1");
        assertEquals(0, count("custody_gas_usage"));
    }

    @Test void eventFailureRollsBackPrincipalGasAndProjectionTogether() {
        reserve();
        jdbc.update("update withdrawal_order set status='CONFIRMED' where tenant_id=? and order_no=?", TENANT, orderNo);
        jdbc.update("""
                insert into evm_tx(chain, tx_hash, from_address, to_address, asset_symbol,
                    amount, fee, confirmations, status)
                values ('ETH', 'gas-test-hash', 'customer', 'destination', 'USDT', 3, 0.04, 12, 'CONFIRMED')
                """);
        var projection = CustodyTestSupport.withdrawalReconciliation(jdbc,
                new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper());
        jdbc.execute("alter table custody_event rename to unavailable_gas_events");
        try { assertThrows(RuntimeException.class, () -> projection.project(TENANT, operationId)); }
        finally { jdbc.execute("alter table unavailable_gas_events rename to custody_event"); }
        assertEquals("SENT", jdbc.queryForObject("select status from custody_withdrawal where id=?", String.class, operationId));
        assertEquals("RESERVED", usageStatus());
        assertBalance("0.9", "0.1", "1");
        assertEquals(0, count("custody_ledger_entry"));
        assertTrue(projection.project(TENANT, operationId));
        assertEquals("SETTLED", usageStatus());
        assertBalance("0.96", "0", "0.96");
        assertEquals(2, count("custody_ledger_entry"));
    }

    private GasUsageRecord reserve() {
        return accounting.reserve(TENANT, "WITHDRAWAL", operationId, RESERVED);
    }

    private GasUsageRecord settle(String amount) {
        return accounting.settle(TENANT, "WITHDRAWAL", operationId,
                new BigDecimal(amount), "CHAIN_CONFIRMED", "gas-test-hash");
    }

    private void expireReservation() {
        jdbc.update("update custody_gas_usage set created_at=now()-interval '1 hour'");
    }

    private String usageStatus() {
        return jdbc.queryForObject("select status from custody_gas_usage where operation_id=?", String.class, operationId);
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    private void assertBalance(String available, String locked, String total) {
        var row = jdbc.queryForMap("select available_balance, locked_balance, total_balance from ledger_balance where tenant_id=? and account_id=?", TENANT, gasAccount);
        assertEquals(0, new BigDecimal(available).compareTo((BigDecimal) row.get("available_balance")));
        assertEquals(0, new BigDecimal(locked).compareTo((BigDecimal) row.get("locked_balance")));
        assertEquals(0, new BigDecimal(total).compareTo((BigDecimal) row.get("total_balance")));
    }

    private UUID address(String account) {
        int namespace = jdbc.queryForObject("select derivation_namespace from custody_tenant where id=?", Integer.class, TENANT);
        int subject = jdbc.queryForObject("select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        long chainAddress = jdbc.queryForObject("""
                insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, derivation_path, wallet_role, enabled)
                values (?, 'ETH', 'ETH', ?, ?, ?, 0, ?, 'gas-test', 'DEPOSIT', true) returning id
                """, Long.class, TENANT, account, Integer.toUnsignedLong(subject), namespace, account);
        UUID address = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, derivation_subject, derivation_child)
                values (?, ?, ?, 'ETH', 'qa', ?, ?, 'API', ?, 0)
                """, address, TENANT, chainAddress, account, account, subject);
        return address;
    }
}
