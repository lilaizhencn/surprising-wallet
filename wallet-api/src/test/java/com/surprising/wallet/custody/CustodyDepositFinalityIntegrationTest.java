package com.surprising.wallet.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.dao.DepositCreditObserver;
import com.surprising.wallet.service.dao.DepositReorgObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.repository.CustodyAssetDashboardRepository;
import com.surprising.wallet.custody.observer.CustodyDepositCreditObserver;
import com.surprising.wallet.custody.observer.CustodyDepositReorgObserver;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.repository.CustodyTenantChainRepository;

class CustodyDepositFinalityIntegrationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = CustodyIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CustodyIntegrationDatabase.reset(dataSource);
    }

    @Test
    void creditedDepositReorgCreatesCompensationDeficitAndRecreditsOnce() {
        transactions.executeWithoutResult(status -> {
            Fixture fixture = fixture();
            ChainJdbcRepository chains = repository();
            long height = 900_001L;
            String originalBlock = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            String replacementBlock = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            DepositEvent original = event(fixture.address(), "0xdeposit-original",
                    new BigDecimal("10"), height, originalBlock);

            chains.observeCanonicalBlock("ETH", "evm-canonical", height, originalBlock, "0xparent");
            assertTrue(chains.recordAndCreditDeposit(original, 0L, 12, fixture.accountId()));
            assertTrue(chains.debitLedgerBalance(
                    fixture.tenantId(), "ETH", "ETH", fixture.accountId(), new BigDecimal("7")));

            var reorg = chains.observeCanonicalBlock(
                    "ETH", "evm-canonical", height, replacementBlock, "0xparent");
            assertTrue(reorg.reorg());
            assertEquals(1, reorg.reversedDepositCount());
            assertEquals("REORGED", value("""
                    select status from deposit_record where chain='ETH' and tx_hash='0xdeposit-original'
                    """, String.class));
            assertFalse(value("""
                    select credited from deposit_record where chain='ETH' and tx_hash='0xdeposit-original'
                    """, Boolean.class));
            assertAmount("0", balance(fixture));
            assertAmount("7", value("""
                    select deficit_amount - recovered_amount from custody_reorg_deficit
                     where tenant_id = ? and status = 'OPEN'
                    """, BigDecimal.class, fixture.tenantId()));
            assertEquals(1, value("""
                    select count(*) from custody_ledger_entry
                     where tenant_id = ? and entry_type = 'DEPOSIT_REORG_REVERSAL'
                       and direction = 'DEBIT' and amount = 10
                    """, Integer.class, fixture.tenantId()));
            assertEquals(1, value("""
                    select count(*) from custody_event
                     where tenant_id = ? and event_type = 'DEPOSIT.REORGED'
                    """, Integer.class, fixture.tenantId()));
            assertTrue(new CustodyRepository(jdbc).hasOpenReorgDeficit(
                    fixture.tenantId(), fixture.custodyAddressId(), "ETH", "ETH"));
            var dashboardDeficits = new CustodyAssetDashboardRepository(jdbc)
                    .openReorgDeficits(fixture.tenantId());
            assertEquals(1, dashboardDeficits.size());
            assertAmount("7", dashboardDeficits.getFirst().outstandingAmount());
            assertEquals(1, chains.createTenantWithdrawalOrder(
                    fixture.tenantId(), "reorg-blocked-withdrawal", 1L,
                    "ETH", "ETH", fixture.address(), fixture.accountId(),
                    "0x2222222222222222222222222222222222222222",
                    BigDecimal.ONE, BigDecimal.ZERO));
            chains.updateWithdrawalStatus(
                    fixture.tenantId(), "ETH", "reorg-blocked-withdrawal",
                    "FROZEN", fixture.address(), null, null);
            assertEquals(0, chains.claimWithdrawalSigning(
                    fixture.tenantId(), "ETH", "reorg-blocked-withdrawal", fixture.address()));

            credit(chains, fixture, "0xdeposit-five", new BigDecimal("5"), height + 1, "0xblock-five");
            assertAmount("0", balance(fixture));
            assertAmount("2", openDeficit(fixture));
            credit(chains, fixture, "0xdeposit-two", new BigDecimal("2"), height + 2, "0xblock-two");
            assertAmount("0", balance(fixture));
            assertEquals(0, value("""
                    select count(*) from custody_reorg_deficit
                     where tenant_id = ? and status = 'OPEN'
                    """, Integer.class, fixture.tenantId()));

            chains.observeCanonicalBlock("ETH", "evm-canonical", height + 3,
                    "0xblock-reappeared", "0xblock-two");
            DepositEvent reappeared = event(fixture.address(), original.txId(),
                    original.amount(), height + 3, "0xblock-reappeared");
            assertTrue(chains.recordAndCreditDeposit(
                    reappeared, 0L, 12, fixture.accountId()));
            assertFalse(chains.recordAndCreditDeposit(
                    reappeared, 0L, 12, fixture.accountId()));
            assertAmount("10", balance(fixture));
            assertEquals(2, value("""
                    select credit_generation from deposit_record
                     where chain='ETH' and tx_hash='0xdeposit-original'
                    """, Integer.class));
            assertEquals(2, value("""
                    select count(*) from custody_event
                     where tenant_id = ? and event_type = 'DEPOSIT.CONFIRMED'
                       and payload #>> '{data,txHash}' = '0xdeposit-original'
                    """, Integer.class, fixture.tenantId()));

            assertTrue(chains.debitLedgerBalance(
                    fixture.tenantId(), "ETH", "ETH", fixture.accountId(), new BigDecimal("4")));
            chains.observeCanonicalBlock("ETH", "evm-canonical", height + 3,
                    "0xsecond-replacement", "0xblock-two");
            assertAmount("0", balance(fixture));
            assertAmount("4", openDeficit(fixture));
            assertEquals(0, value("""
                    select recovered_amount from custody_reorg_deficit
                     where tenant_id = ? and status = 'OPEN'
                    """, BigDecimal.class, fixture.tenantId()).compareTo(BigDecimal.ZERO));
            status.setRollbackOnly();
        });
    }

    private void credit(ChainJdbcRepository chains, Fixture fixture, String txHash,
                        BigDecimal amount, long height, String blockHash) {
        chains.observeCanonicalBlock("ETH", "evm-canonical", height, blockHash, "0xparent");
        assertTrue(chains.recordAndCreditDeposit(
                event(fixture.address(), txHash, amount, height, blockHash),
                0L, 12, fixture.accountId()));
    }

    private ChainJdbcRepository repository() {
        ObjectMapper objectMapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        CustodyRepository custody = new CustodyRepository(jdbc);
        CustodyDepositCreditObserver credit = new CustodyDepositCreditObserver(
                jdbc, objectMapper, custody, new CustodyTenantChainRepository(jdbc));
        CustodyDepositReorgObserver reorg = new CustodyDepositReorgObserver(
                jdbc, custody, objectMapper);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("credit", credit);
        beans.addBean("reorg", reorg);
        return new ChainJdbcRepository(
                jdbc, beans.getBeanProvider(DepositCreditObserver.class),
                beans.getBeanProvider(DepositReorgObserver.class));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID custodyAddressId = UUID.randomUUID();
        int namespace = value("select nextval('custody_derivation_namespace_seq')::integer", Integer.class);
        int subject = value("select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        String address = "0x" + suffix + suffix.substring(0, 8);
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, ?, 'Finality integration', ?)
                """, tenantId, "finality-" + suffix.substring(0, 16), namespace);
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, ?, ?, 'Finality administrator', 'hash', 'TENANT_ADMIN', 'ACTIVE')
                """, userId, tenantId, "finality-" + suffix.substring(0, 10) + "@test.invalid");
        new CustodyTenantChainRepository(jdbc).setStatus(tenantId, "ETH", "ACTIVE", userId);
        Long chainAddressId = value("""
                insert into chain_address(
                    tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                    address, derivation_path, wallet_role, enabled)
                values (?, 'ETH', 'ETH', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                returning id
                """, Long.class, tenantId, address, Integer.toUnsignedLong(subject), namespace,
                address, "m/44/60/" + namespace + "/" + subject + "/0");
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, status, derivation_subject, derivation_child)
                values (?, ?, ?, 'ETH', 'integration', ?, 'finality-user',
                        'API', 'ACTIVE', ?, 0)
                """, custodyAddressId, tenantId, chainAddressId, address, subject);
        jdbc.update("""
                insert into custody_webhook_endpoint(
                    id, tenant_id, name, url, secret_ciphertext, status, verified_at)
                values (?, ?, 'Finality events', 'https://example.invalid/hook',
                        'ciphertext', 'ACTIVE', now())
                """, UUID.randomUUID(), tenantId);
        return new Fixture(tenantId, custodyAddressId, address, address);
    }

    private DepositEvent event(String address, String txHash, BigDecimal amount,
                               long height, String blockHash) {
        return new DepositEvent(ChainType.ETH, "ETH", txHash,
                "0x2222222222222222222222222222222222222222", address,
                amount, height, blockHash, 12, null, "{\"integration\":true}");
    }

    private BigDecimal balance(Fixture fixture) {
        return value("""
                select available_balance from ledger_balance
                 where tenant_id = ? and chain='ETH' and asset_symbol='ETH' and account_id = ?
                """, BigDecimal.class, fixture.tenantId(), fixture.accountId());
    }

    private BigDecimal openDeficit(Fixture fixture) {
        return value("""
                select deficit_amount - recovered_amount from custody_reorg_deficit
                 where tenant_id = ? and status = 'OPEN'
                """, BigDecimal.class, fixture.tenantId());
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private <T> T value(String sql, Class<T> type, Object... args) {
        return jdbc.queryForObject(sql, type, args);
    }

    private record Fixture(UUID tenantId, UUID custodyAddressId,
                           String address, String accountId) {
    }
}
