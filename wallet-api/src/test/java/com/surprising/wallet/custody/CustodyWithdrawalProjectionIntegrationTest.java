package com.surprising.wallet.custody;

import com.surprising.wallet.model.custody.WithdrawalStatusChange;
import com.surprising.wallet.repository.CustodyRepository;
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

/** Withdrawal projection records settled money without debiting the customer a second time. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyWithdrawalProjectionIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private CustodyRepository repository;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(CustodyRepository.class, () -> CustodyTestSupport.repository(jdbc));
        context.refresh();
        repository = context.getBean(CustodyRepository.class);
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void confirmedWithdrawalWithoutGasRecordsPrincipalAndFeeExactlyOnce() {
        var change = withdrawal("ETH", "same-order");
        assertTrue(project(change));
        assertEquals(1, entries(change));
        assertFalse(project(change));
        assertEquals("WITHDRAWAL", jdbc.queryForObject(
                "select entry_type from custody_ledger_entry where tenant_id=?", String.class, TENANT));
        assertEquals(0, new BigDecimal("3.25").compareTo(jdbc.queryForObject(
                "select amount from custody_ledger_entry where tenant_id=?", BigDecimal.class, TENANT)));
        assertEquals(change.debitAccountId(), jdbc.queryForObject(
                "select account_id from custody_ledger_entry where tenant_id=?", String.class, TENANT));
        assertEquals("DEBIT", jdbc.queryForObject(
                "select direction from custody_ledger_entry where tenant_id=?", String.class, TENANT));
        assertEquals(1, events());
        assertCustomerAlreadySettled(change);
    }

    @Test void equalOrderNumbersInDifferentTenantsAndChainsHaveIndependentLedgerEntries() {
        var first = withdrawal("ETH", "same-order");
        UUID otherTenant = UUID.randomUUID();
        new com.surprising.wallet.repository.CustodyTenantTableRepository(jdbc)
                .insert(otherTenant, "projection-" + otherTenant, "Other projection tenant");
        var second = withdrawal(otherTenant, "TRON", "same-order");
        assertTrue(project(first));
        assertTrue(project(second));
        assertEquals(1, entries(first));
        assertEquals(1, entries(second));
        assertEquals(2, jdbc.queryForObject("select count(*) from custody_ledger_entry", Integer.class));
        assertCustomerAlreadySettled(first);
        assertCustomerAlreadySettled(second);
    }

    @Test void ledgerFailureRollsBackProjectionAndEventWithoutAnOuterTransaction() {
        var change = withdrawal("ETH", "rollback-order");
        jdbc.execute("alter table custody_ledger_entry rename to unavailable_withdrawal_ledger");
        try {
            assertThrows(RuntimeException.class, () -> project(change));
            assertEquals("FROZEN", jdbc.queryForObject(
                    "select status from custody_withdrawal where id=?", String.class, change.id()));
            assertEquals(0, events());
            assertCustomerAlreadySettled(change);
        } finally {
            jdbc.execute("alter table unavailable_withdrawal_ledger rename to custody_ledger_entry");
        }
        assertTrue(project(change));
        assertEquals(1, entries(change));
        assertEquals(1, events());
    }

    @Test void concurrentProjectionAndStaleReplayCommitOneLedgerEntryAndOneEvent() throws Exception {
        var change = withdrawal("ETH", "concurrent-order");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 24)
                    .mapToObj(i -> executor.submit(() -> project(change))).toList();
            int applied = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) applied++;
            assertEquals(1, applied);
        }
        assertFalse(project(change));
        assertEquals(1, entries(change));
        assertEquals(1, events());
        assertCustomerAlreadySettled(change);
    }

    @Test void foreignTenantCannotAdvanceAnotherTenantsProjection() {
        var change = withdrawal("ETH", "owned-order");
        UUID otherTenant = UUID.randomUUID();
        new com.surprising.wallet.repository.CustodyTenantTableRepository(jdbc)
                .insert(otherTenant, "projection-" + otherTenant, "Other projection tenant");
        var foreign = new WithdrawalStatusChange(change.id(), otherTenant, change.custodyAddressId(),
                change.orderNo(), change.externalReference(), change.chain(), change.assetSymbol(),
                change.toAddress(), change.amount(), change.fee(), change.previousStatus(), change.nextStatus(),
                change.txHash(), change.errorMessage(), change.debitAccountId(), change.addressSource());
        assertFalse(project(foreign));
        assertEquals("FROZEN", jdbc.queryForObject(
                "select status from custody_withdrawal where id=?", String.class, change.id()));
        assertEquals(0, entries(change));
        assertEquals(0, events());
        assertCustomerAlreadySettled(change);
    }

    @Test void projectionDoesNotWriteAnOlderSnapshotBackToTheChainOrder() {
        var confirmed = withdrawal("ETH", "advanced-order");
        var earlier = new WithdrawalStatusChange(confirmed.id(), confirmed.tenantId(), confirmed.custodyAddressId(),
                confirmed.orderNo(), confirmed.externalReference(), confirmed.chain(), confirmed.assetSymbol(),
                confirmed.toAddress(), confirmed.amount(), confirmed.fee(), "FROZEN", "SENT",
                confirmed.txHash(), null, confirmed.debitAccountId(), confirmed.addressSource());
        assertTrue(repository.applyWithdrawalStatusChange(earlier, UUID.randomUUID(), "WITHDRAWAL.BROADCAST", "{}"));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "select status from withdrawal_order where tenant_id=? and order_no=?", String.class, TENANT, confirmed.orderNo()));
        var current = repository.findWithdrawalStatusChanges(100).stream()
                .filter(change -> change.id().equals(confirmed.id())).findFirst().orElseThrow();
        assertTrue(project(current));
        assertFalse(project(earlier));
        assertEquals(1, entries(confirmed));
        assertEquals(2, events());
        assertCustomerAlreadySettled(confirmed);
    }

    private boolean project(WithdrawalStatusChange change) {
        return repository.applyWithdrawalStatusChange(change, UUID.randomUUID(), "WITHDRAWAL.CONFIRMED", "{}");
    }

    private WithdrawalStatusChange withdrawal(String chain, String orderNo) {
        return withdrawal(TENANT, chain, orderNo);
    }

    private WithdrawalStatusChange withdrawal(UUID tenantId, String chain, String orderNo) {
        String account = "projection-" + UUID.randomUUID();
        int namespace = jdbc.queryForObject("select derivation_namespace from custody_tenant where id=?",
                Integer.class, tenantId);
        int subject = jdbc.queryForObject("select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        long chainAddressId = jdbc.queryForObject("""
                insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, derivation_path, wallet_role, enabled)
                values (?, ?, 'USDT', ?, ?, ?, 0, ?, 'projection-test', 'DEPOSIT', true) returning id
                """, Long.class, tenantId, chain, account, Integer.toUnsignedLong(subject), namespace, account);
        UUID addressId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, derivation_subject, derivation_child)
                values (?, ?, ?, ?, 'qa', ?, ?, 'API', ?, 0)
                """, addressId, tenantId, chainAddressId, chain, account, account, subject);
        jdbc.update("""
                insert into ledger_balance(tenant_id, chain, asset_symbol, account_id,
                    available_balance, locked_balance, total_balance)
                values (?, ?, 'USDT', ?, 6.75, 0, 6.75)
                """, tenantId, chain, account);
        jdbc.update("""
                insert into withdrawal_order(tenant_id, order_no, user_id, chain, asset_symbol,
                    from_address, debit_account_id, to_address, amount, fee, status, tx_hash)
                values (?, ?, ?, ?, 'USDT', ?, ?, 'destination', 3, 0.25, 'CONFIRMED', 'confirmed-hash')
                """, tenantId, orderNo, Integer.toUnsignedLong(subject), chain, account, account);
        UUID withdrawalId = UUID.randomUUID();
        repository.insertCustodyWithdrawal(withdrawalId, tenantId, addressId, orderNo,
                null, null, chain, "USDT", "destination", new BigDecimal("3"), new BigDecimal("0.25"),
                "FROZEN", "API_KEY", "projection-test");
        return repository.findWithdrawalStatusChanges(100).stream()
                .filter(change -> change.id().equals(withdrawalId)).findFirst().orElseThrow();
    }

    private int entries(WithdrawalStatusChange change) {
        return jdbc.queryForObject("""
                select count(*) from custody_ledger_entry where tenant_id=? and custody_address_id=?
                """, Integer.class, change.tenantId(), change.custodyAddressId());
    }

    private int events() { return jdbc.queryForObject("select count(*) from custody_event", Integer.class); }

    private void assertCustomerAlreadySettled(WithdrawalStatusChange change) {
        var balance = jdbc.queryForMap("""
                select available_balance, locked_balance, total_balance from ledger_balance
                 where tenant_id=? and chain=? and asset_symbol='USDT' and account_id=?
                """, change.tenantId(), change.chain(), change.debitAccountId());
        assertEquals(0, new BigDecimal("6.75").compareTo((BigDecimal) balance.get("available_balance")));
        assertEquals(0, new BigDecimal("6.75").compareTo((BigDecimal) balance.get("total_balance")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) balance.get("locked_balance")));
    }
}
