package com.surprising.wallet.custody;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.repository.CustodyWithdrawalRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.service.DepositService;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code CustodyDepositFinalityIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyDepositFinalityIntegrationTest {
    /**
     * 保存 {@code jdbc}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private JdbcTemplate jdbc;
    /**
     * 保存 {@code transactions}，用于标识测试中的交易、区块或业务记录。
     */
    private TransactionTemplate transactions;

    /**
     * 验证 {@code setUp} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = CustodyIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CustodyIntegrationDatabase.reset(dataSource);
    }

    /**
     * 验证 {@code creditedDepositReorgCreatesCompensationDeficitAndRecreditsOnce} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void creditedDepositReorgCreatesCompensationDeficitAndRecreditsOnce() {
        transactions.executeWithoutResult(status -> {
            Fixture fixture = fixture();
            ChainRepositoryFixture chains = repository();
            long height = 900_001L;
            String originalBlock = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            String replacementBlock = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            DepositEvent original = event(fixture.address(), "0xdeposit-original",
                    new BigDecimal("10"), height, originalBlock);

            chains.depositService.observeCanonicalBlock("ETH", "evm-canonical", height, originalBlock, "0xparent");
            assertTrue(chains.depositService.recordAndCredit(original, 0L, 12, fixture.accountId()));
            assertTrue(chains.ledgerBalanceRepository.debit("ETH", "ETH", fixture.accountId(), new BigDecimal("7"), fixture.tenantId()));

            var reorg = chains.depositService.observeCanonicalBlock("ETH", "evm-canonical", height, replacementBlock, "0xparent");
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
            assertTrue(new com.surprising.wallet.repository.CustodyReorgDeficitRepository(jdbc).existsOpen(fixture.tenantId(), "ETH", "ETH", fixture.accountId()));
            var dashboardDeficits = new com.surprising.wallet.repository.CustodyReorgDeficitRepository(jdbc)
                    .listOpen(fixture.tenantId());
            assertEquals(1, dashboardDeficits.size());
            assertAmount("7", dashboardDeficits.getFirst().outstandingAmount());
            assertTrue(chains.withdrawalOrderRepository.createForTenant(
                            fixture.tenantId(),
                            "reorg-blocked-withdrawal",
                            1L,
                            "ETH",
                            "ETH",
                            fixture.address(),
                            fixture.accountId(),
                            "0x2222222222222222222222222222222222222222",
                            BigDecimal.ONE,
                            BigDecimal.ZERO) > 0L);
            chains.withdrawalOrderRepository.updateStatus(
                    fixture.tenantId(),
                    "ETH",
                    "reorg-blocked-withdrawal",
                    "FROZEN",
                    fixture.address(),
                    null,
                    null);
            assertEquals(0, chains.withdrawalClaimService.claim(fixture.tenantId(), "ETH", "reorg-blocked-withdrawal", fixture.address()));

            credit(chains, fixture, "0xdeposit-five", new BigDecimal("5"), height + 1, "0xblock-five");
            assertAmount("0", balance(fixture));
            assertAmount("2", openDeficit(fixture));
            credit(chains, fixture, "0xdeposit-two", new BigDecimal("2"), height + 2, "0xblock-two");
            assertAmount("0", balance(fixture));
            assertEquals(0, value("""
                    select count(*) from custody_reorg_deficit
                     where tenant_id = ? and status = 'OPEN'
                    """, Integer.class, fixture.tenantId()));

            chains.depositService.observeCanonicalBlock("ETH", "evm-canonical", height + 3, "0xblock-reappeared", "0xblock-two");
            DepositEvent reappeared = event(fixture.address(), original.txId(),
                    original.amount(), height + 3, "0xblock-reappeared");
            assertTrue(chains.depositService.recordAndCredit(reappeared, 0L, 12, fixture.accountId()));
            assertFalse(chains.depositService.recordAndCredit(reappeared, 0L, 12, fixture.accountId()));
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

            assertTrue(chains.ledgerBalanceRepository.debit("ETH", "ETH", fixture.accountId(), new BigDecimal("4"), fixture.tenantId()));
            chains.depositService.observeCanonicalBlock("ETH", "evm-canonical", height + 3, "0xsecond-replacement", "0xblock-two");
            assertAmount("0", balance(fixture));
            assertAmount("4", openDeficit(fixture));
            assertEquals(0, value("""
                    select recovered_amount from custody_reorg_deficit
                     where tenant_id = ? and status = 'OPEN'
                    """, BigDecimal.class, fixture.tenantId()).compareTo(BigDecimal.ZERO));
            status.setRollbackOnly();
        });
    }

    @Test
    void reorgProjectionFailureRollsBackLedgerDeficitAndCanonicalState() {
        Fixture fixture = transactions.execute(status -> {
            Fixture created = fixture();
            ChainRepositoryFixture chains = repository();
            credit(chains, created, "0xrollback-reorg", new BigDecimal("10"), 910_001L, "0xoriginal");
            assertTrue(chains.ledgerBalanceRepository.debit("ETH", "ETH", created.accountId(), new BigDecimal("7"), created.tenantId()));
            return created;
        });
        com.surprising.wallet.service.CustodyEventService failingEvents =
                new com.surprising.wallet.service.CustodyEventService(
                        new com.surprising.wallet.repository.CustodyEventRepository(jdbc),
                        new com.surprising.wallet.repository.CustodyWebhookEndpointRepository(jdbc),
                        new com.surprising.wallet.repository.CustodyWebhookDeliveryRepository(jdbc)) {
            @Override
            public UUID publish(UUID id, UUID tenantId, String eventType,
                                                   String aggregateType, String aggregateId,
                                                   String payload, boolean apiSource) {
                throw new IllegalStateException("reorg event failure");
            }
        };
        var mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        try (var context = com.surprising.wallet.testutil.DepositTestSupport.context(jdbc, failingEvents, mapper)) {
            var deposits = context.getBean(com.surprising.wallet.service.DepositService.class);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    deposits.observeCanonicalBlock("ETH", "evm-canonical", 910_001L, "0xreplacement", "0xparent"));
        }
        assertAmount("3", balance(fixture));
        assertEquals("CREDITED", value("select status from deposit_record where tx_hash = '0xrollback-reorg'", String.class));
        assertEquals("CONFIRMED", value("select status from custody_deposit where tenant_id = ?", String.class, fixture.tenantId()));
        assertEquals(0, value("select count(*) from custody_reorg_deficit where tenant_id = ?", Integer.class, fixture.tenantId()));
        assertEquals(0, value("""
                select count(*) from custody_ledger_entry
                 where tenant_id = ? and entry_type = 'DEPOSIT_REORG_REVERSAL'
                """, Integer.class, fixture.tenantId()));
        try (var context = com.surprising.wallet.testutil.DepositTestSupport.context(
                jdbc,
                com.surprising.wallet.testutil.CustodyTestSupport.events(jdbc),
                mapper)) {
            var deposits = context.getBean(com.surprising.wallet.service.DepositService.class);
            assertEquals(1, deposits.observeCanonicalBlock("ETH", "evm-canonical", 910_001L, "0xreplacement", "0xparent")
                    .reversedDepositCount());
            assertFalse(deposits.observeCanonicalBlock("ETH", "evm-canonical", 910_001L, "0xreplacement", "0xparent").reorg());
        }
        assertAmount("0", balance(fixture));
        assertAmount("7", openDeficit(fixture));
    }

    /**
     * 验证 {@code credit} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private void credit(ChainRepositoryFixture chains, Fixture fixture, String txHash,
                        BigDecimal amount, long height, String blockHash) {
        chains.depositService.observeCanonicalBlock("ETH", "evm-canonical", height, blockHash, "0xparent");
        assertTrue(chains.depositService.recordAndCredit(event(fixture.address(), txHash, amount, height, blockHash), 0L, 12, fixture.accountId()));
    }

    /**
     * 验证 {@code repository} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private ChainRepositoryFixture repository() {
        return com.surprising.wallet.testutil.DepositTestSupport.repository(jdbc);
    }

    /**
     * 验证 {@code fixture} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID custodyAddressId = UUID.randomUUID();
        jdbc.update("""
                update chain_profile
                   set scan_enabled = true
                 where chain = 'ETH' and enabled = true
                """);
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
        new CustodyTenantChainTableRepository(jdbc).setStatus(tenantId, "ETH", "ACTIVE", userId);
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

    /**
     * 验证 {@code event} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private DepositEvent event(String address, String txHash, BigDecimal amount,
                               long height, String blockHash) {
        return new DepositEvent(ChainType.ETH, "ETH", txHash,
                "0x2222222222222222222222222222222222222222", address,
                amount, height, blockHash, 12, null, "{\"integration\":true}");
    }

    /**
     * 验证 {@code balance} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private BigDecimal balance(Fixture fixture) {
        return value("""
                select available_balance from ledger_balance
                 where tenant_id = ? and chain='ETH' and asset_symbol='ETH' and account_id = ?
                """, BigDecimal.class, fixture.tenantId(), fixture.accountId());
    }

    /**
     * 验证 {@code openDeficit} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private BigDecimal openDeficit(Fixture fixture) {
        return value("""
                select deficit_amount - recovered_amount from custody_reorg_deficit
                 where tenant_id = ? and status = 'OPEN'
                """, BigDecimal.class, fixture.tenantId());
    }

    /**
     * 验证 {@code assertAmount} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    /**
     * 验证 {@code value} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private <T> T value(String sql, Class<T> type, Object... args) {
        return jdbc.queryForObject(sql, type, args);
    }

    private record Fixture(UUID tenantId, UUID custodyAddressId,
                           String address, String accountId) {
    }
}
