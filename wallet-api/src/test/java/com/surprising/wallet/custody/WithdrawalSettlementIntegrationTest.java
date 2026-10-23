package com.surprising.wallet.custody;

import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.service.WithdrawalSettlementService;
import org.junit.jupiter.api.*;
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
import static org.mockito.Mockito.*;

/** Standalone service transactions preserve tenant fences, exactly-once debit and rollback. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class WithdrawalSettlementIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BigDecimal DEBIT = new BigDecimal("1.25");
    private static final String ORDER = "SETTLEMENT-TEST", ACCOUNT = "shared-account", HASH = "0xconfirmed";
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private WithdrawalOrderRepository orders;
    private WithdrawalSettlementService settlements;
    private UUID otherTenant;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        orders = spy(new WithdrawalOrderRepository(jdbc));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(WithdrawalOrderRepository.class, () -> orders);
        context.registerBean(LedgerBalanceRepository.class, () -> new LedgerBalanceRepository(jdbc));
        context.registerBean(WithdrawalSettlementService.class);
        context.refresh();
        settlements = context.getBean(WithdrawalSettlementService.class);
        otherTenant = UUID.randomUUID();
        new CustodyTenantTableRepository(jdbc).insert(otherTenant, "settle-" + otherTenant, "Other tenant");
        for (UUID tenant : new UUID[]{TENANT, otherTenant}) {
            jdbc.update("""
                    insert into ledger_balance(tenant_id, chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values (?, 'ETH', 'ETH', ?, 0, 3.75, 3.75)
                    """, tenant, tenant.equals(TENANT) ? ACCOUNT : ACCOUNT + "-foreign");
        }
        assertEquals(1, orders.createForTenant(TENANT, ORDER, 1, "ETH", "ETH", ACCOUNT, ACCOUNT,
                "destination", BigDecimal.ONE, new BigDecimal("0.25")));
        assertEquals(1, orders.updateStatus(TENANT, "ETH", ORDER, "SENT", ACCOUNT, HASH, null));
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void repeatedConfirmationDebitsOnlyOnce() {
        assertTrue(confirm());
        assertFalse(confirm());
        assertEquals("CONFIRMED", status());
        assertBalance(TENANT, "2.50");
        assertBalance(otherTenant, "3.75");
    }

    @Test void tenantHashAndStatusMismatchLeaveTheLedgerUntouched() {
        assertThrows(IllegalStateException.class, () -> settlements.confirm(otherTenant,
                "ETH", ORDER, HASH, "ETH", ACCOUNT, DEBIT));
        assertThrows(IllegalStateException.class, () -> settlements.confirm(TENANT,
                "ETH", ORDER, "0xwrong", "ETH", ACCOUNT, DEBIT));
        orders.updateStatus(TENANT, "ETH", ORDER, "SIGNING", ACCOUNT, HASH, null);
        assertThrows(IllegalStateException.class, this::confirm);
        assertBalance(TENANT, "3.75");
        assertBalance(otherTenant, "3.75");
    }

    @Test void lostOrderTransitionRollsBackTheAlreadyAppliedLedgerDebitWithoutOuterTransaction() {
        doReturn(0).when(orders).markConfirmed(TENANT, "ETH", ORDER, HASH);
        assertThrows(IllegalStateException.class, this::confirm);
        assertEquals("SENT", status());
        assertBalance(TENANT, "3.75");
        doCallRealMethod().when(orders).markConfirmed(TENANT, "ETH", ORDER, HASH);
        assertTrue(confirm());
        assertBalance(TENANT, "2.50");
    }

    @Test void insufficientFrozenBalanceDoesNotConfirmTheOrder() {
        jdbc.update("update ledger_balance set locked_balance=0, available_balance=total_balance where tenant_id=?", TENANT);
        assertThrows(IllegalStateException.class, this::confirm);
        assertEquals("SENT", status());
        assertEquals(0, new BigDecimal("3.75").compareTo(jdbc.queryForObject(
                "select total_balance from ledger_balance where tenant_id=?", BigDecimal.class, TENANT)));
    }

    @Test void concurrentConfirmationsCannotCommitMoreThanOneDebit() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 24).mapToObj(i -> executor.submit(() -> {
                try { return confirm(); }
                catch (IllegalStateException losingTransition) { return false; }
            })).toList();
            int committed = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) committed++;
            assertEquals(1, committed);
        }
        assertEquals("CONFIRMED", status());
        assertBalance(TENANT, "2.50");
        assertBalance(otherTenant, "3.75");
    }

    private boolean confirm() { return settlements.confirm(TENANT, "ETH", ORDER, HASH, "ETH", ACCOUNT, DEBIT); }
    private String status() { return orders.findStatus("ETH", ORDER).orElseThrow(); }

    private void assertBalance(UUID tenant, String expected) {
        var balance = jdbc.queryForMap("select locked_balance,total_balance from ledger_balance where tenant_id=?", tenant);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) balance.get("locked_balance")));
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) balance.get("total_balance")));
    }
}
