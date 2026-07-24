package com.surprising.wallet.custody;

import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.repository.CustodyRepository;

class CustodyTenantIsolationIntegrationTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void collectionStateCannotBeReadClaimedOrUpdatedByAnotherTenant() {
        UUID ownerTenant = createTenant();
        UUID otherTenant = createTenant();
        String accountId = "tenant-collection-" + UUID.randomUUID();
        UUID custodyAddressId = createAddress(ownerTenant, accountId);
        String collectionNo = "tenant-scoped-" + UUID.randomUUID();
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);

        assertEquals(1, repository.createCollectionRecord(
                ownerTenant, custodyAddressId, collectionNo, "ETH", "USDT",
                accountId, "0x0000000000000000000000000000000000000001",
                BigDecimal.ONE, BigDecimal.ZERO, null));

        assertEquals(0, repository.claimCollectionSigning(
                otherTenant, "ETH", collectionNo, null));
        assertTrue(repository.findCollectionStatus(
                otherTenant, "ETH", collectionNo).isEmpty());
        assertEquals(0, repository.updateCollectionStatus(
                otherTenant, "ETH", collectionNo, "SENT", "0xwrong", null, null));
        assertTrue(repository.findCollectionTxHash(
                otherTenant, "ETH", collectionNo).isEmpty());

        assertEquals(1, repository.claimCollectionSigning(
                ownerTenant, "ETH", collectionNo, null));
        assertEquals(1, repository.updateCollectionStatus(
                ownerTenant, "ETH", collectionNo, "SENT", "0xowner", null, null));
        assertEquals(0, repository.markCollectionConfirmed(
                otherTenant, "ETH", collectionNo, "0xowner"));
        assertEquals(1, repository.markCollectionConfirmed(
                ownerTenant, "ETH", collectionNo, "0xowner"));
        assertEquals("CONFIRMED", repository.findCollectionStatus(
                ownerTenant, "ETH", collectionNo).orElseThrow());
        assertEquals("0xowner", repository.findCollectionTxHash(
                ownerTenant, "ETH", collectionNo).orElseThrow());
    }

    @Test
    void collectionApiRejectsMissingTenantContext() {
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);

        assertThrows(NullPointerException.class, () -> repository.createCollectionRecord(
                null, null, "missing-tenant", "ETH", "ETH",
                "0x0000000000000000000000000000000000000001",
                "0x0000000000000000000000000000000000000002",
                BigDecimal.ONE, BigDecimal.ZERO, null));
        assertThrows(NullPointerException.class,
                () -> repository.findCollectionStatus(null, "ETH", "missing-tenant"));
    }

    @Test
    void gasReservationCannotUseLedgerBalanceOwnedByAnotherTenant() {
        UUID gasTenant = createTenant();
        UUID balanceTenant = createTenant();
        String accountId = "0x" + UUID.randomUUID().toString().replace("-", "") + "12345678";
        UUID custodyAddressId = createAddress(gasTenant, accountId);
        UUID gasAccountId = UUID.randomUUID();
        UUID withdrawalId = UUID.randomUUID();
        String orderNo = "tenant-gas-" + UUID.randomUUID();
        CustodyRepository repository = new CustodyRepository(jdbc);

        jdbc.update("""
                insert into custody_gas_account(
                    id, tenant_id, custody_address_id, chain, network,
                    native_symbol, low_balance_threshold, status)
                values (?, ?, ?, 'ETH', 'qa', 'ETH', 0, 'ACTIVE')
                """, gasAccountId, gasTenant, custodyAddressId);
        jdbc.update("""
                insert into ledger_balance(
                    tenant_id, chain, asset_symbol, account_id,
                    available_balance, locked_balance, total_balance)
                values (?, 'ETH', 'ETH', ?, 5, 0, 5)
                """, balanceTenant, accountId);
        jdbc.update("""
                insert into withdrawal_order(
                    tenant_id, order_no, user_id, chain, asset_symbol,
                    from_address, debit_account_id, to_address, amount, fee, status)
                values (?, ?, 1, 'ETH', 'ETH', ?, ?, ?, 1, 0, 'FROZEN')
                """, gasTenant, orderNo, accountId, accountId,
                "0x0000000000000000000000000000000000000002");
        repository.insertCustodyWithdrawal(
                withdrawalId, gasTenant, custodyAddressId, orderNo, null, null,
                "ETH", "ETH", accountId, BigDecimal.ONE, BigDecimal.ZERO,
                "FROZEN", "CONSOLE", "qa");

        assertEquals(0, BigDecimal.ZERO.compareTo(
                repository.requireGasAccount(gasTenant, gasAccountId).availableBalance()));
        assertThrows(IllegalStateException.class, () -> repository.reserveGasUsage(
                gasTenant, withdrawalId, orderNo, "ETH", new BigDecimal("0.1")));
        assertEquals(0, new BigDecimal("5").compareTo(jdbc.queryForObject("""
                select available_balance from ledger_balance
                 where tenant_id = ? and chain = 'ETH' and asset_symbol = 'ETH'
                   and account_id = ?
                """, BigDecimal.class, balanceTenant, accountId)));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from custody_gas_usage
                 where tenant_id = ? and operation_id = ?
                """, Integer.class, gasTenant, withdrawalId));
    }

    @Test
    void singleTransactionWithdrawalSettlesOnceAndOnlyForItsTenant() {
        UUID ownerTenant = createTenant();
        UUID otherTenant = createTenant();
        String accountId = "tenant-withdrawal-" + UUID.randomUUID();
        createAddress(ownerTenant, accountId);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        String orderNo = "tenant-withdrawal-" + UUID.randomUUID();
        String txHash = "0x" + UUID.randomUUID().toString().replace("-", "");
        BigDecimal debit = new BigDecimal("1.25");

        jdbc.update("""
                insert into ledger_balance(
                    tenant_id, chain, asset_symbol, account_id,
                    available_balance, locked_balance, total_balance)
                values (?, 'ETH', 'ETH', ?, 0, ?, ?)
                """, ownerTenant, accountId, debit, debit);
        assertEquals(1, repository.createTenantWithdrawalOrder(
                ownerTenant, orderNo, 1L, "ETH", "ETH", accountId, accountId,
                "0x0000000000000000000000000000000000000002",
                BigDecimal.ONE, new BigDecimal("0.25")));
        assertEquals(1, repository.updateWithdrawalStatus(
                ownerTenant, "ETH", orderNo, "SENT", accountId, txHash, null));

        assertThrows(IllegalStateException.class, () -> repository.confirmWithdrawalAndSettle(
                otherTenant, "ETH", orderNo, txHash, "ETH", accountId, debit));
        assertThrows(IllegalStateException.class, () -> repository.confirmWithdrawalAndSettle(
                ownerTenant, "ETH", orderNo, txHash + "bad", "ETH", accountId, debit));
        assertEquals(0, new BigDecimal("1.25").compareTo(jdbc.queryForObject("""
                select locked_balance from ledger_balance
                 where tenant_id = ? and chain = 'ETH' and asset_symbol = 'ETH'
                   and account_id = ?
                """, BigDecimal.class, ownerTenant, accountId)));

        assertTrue(repository.confirmWithdrawalAndSettle(
                ownerTenant, "ETH", orderNo, txHash, "ETH", accountId, debit));
        assertFalse(repository.confirmWithdrawalAndSettle(
                ownerTenant, "ETH", orderNo, txHash, "ETH", accountId, debit));
        assertEquals("CONFIRMED", repository.findWithdrawalOrder(
                ownerTenant, "ETH", orderNo).orElseThrow().getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject("""
                select locked_balance from ledger_balance
                 where tenant_id = ? and chain = 'ETH' and asset_symbol = 'ETH'
                   and account_id = ?
                """, BigDecimal.class, ownerTenant, accountId)));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject("""
                select total_balance from ledger_balance
                 where tenant_id = ? and chain = 'ETH' and asset_symbol = 'ETH'
                   and account_id = ?
                """, BigDecimal.class, ownerTenant, accountId)));
    }

    @Test
    void bitcoinLikeSigningBatchAndUtxosStayWithinOneTenant() {
        UUID firstTenant = createTenant();
        UUID secondTenant = createTenant();
        String firstAddress = "btc-first-" + UUID.randomUUID();
        String secondAddress = "btc-second-" + UUID.randomUUID();
        createChainAddress(firstTenant, "BTC", firstAddress);
        createChainAddress(secondTenant, "BTC", secondAddress);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);

        assertEquals(1, repository.createTenantWithdrawalOrder(
                firstTenant, "btc-first-order", 11L, "BTC", "BTC",
                firstAddress, firstAddress, "btc-first-recipient",
                new BigDecimal("0.10"), new BigDecimal("0.001")));
        assertEquals(1, repository.updateWithdrawalStatus(
                firstTenant, "BTC", "btc-first-order", "FROZEN", firstAddress, null, null));
        assertEquals(1, repository.createTenantWithdrawalOrder(
                secondTenant, "btc-second-order", 12L, "BTC", "BTC",
                secondAddress, secondAddress, "btc-second-recipient",
                new BigDecimal("0.20"), new BigDecimal("0.001")));
        assertEquals(1, repository.updateWithdrawalStatus(
                secondTenant, "BTC", "btc-second-order", "FROZEN", secondAddress, null, null));

        List<com.surprising.wallet.common.chain.WithdrawalOrderRecord> batch =
                repository.listWithdrawalsForSigning("BTC", "BTC", 10);
        assertEquals(1, batch.size());
        assertEquals(firstTenant, batch.getFirst().getTenantId());

        repository.upsertUtxo("BTC", "BTC", "first-utxo", 0,
                firstAddress, BigDecimal.ONE, 100L, "first-block", 12, true);
        repository.upsertUtxo("BTC", "BTC", "second-utxo", 0,
                secondAddress, new BigDecimal("2"), 100L, "second-block", 12, true);
        var firstUtxos = repository.listSpendableUtxos(
                firstTenant, "BTC", "BTC", 6, 10, 0);
        assertEquals(1, firstUtxos.size());
        assertEquals("first-utxo", firstUtxos.getFirst().getTxId());
        assertEquals(0, repository.lockUtxo(
                secondTenant, "BTC", "first-utxo", 0, "wrong-tenant-lock"));
        assertEquals(1, repository.lockUtxo(
                firstTenant, "BTC", "first-utxo", 0, "owner-lock"));
    }

    private UUID createTenant() {
        UUID tenantId = UUID.randomUUID();
        int namespace = jdbc.queryForObject(
                "select nextval('custody_derivation_namespace_seq')::integer", Integer.class);
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, ?, 'Tenant isolation integration test', ?)
                """, tenantId, "tenant-isolation-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16), namespace);
        return tenantId;
    }

    private UUID createAddress(UUID tenantId, String accountId) {
        int namespace = jdbc.queryForObject(
                "select derivation_namespace from custody_tenant where id = ?",
                Integer.class, tenantId);
        int subject = jdbc.queryForObject(
                "select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        Long chainAddressId = jdbc.queryForObject("""
                insert into chain_address(
                    tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, owner_address, derivation_path,
                    wallet_role, enabled)
                values (?, 'ETH', 'ETH', ?, ?, ?, 0, ?, ?, ?, 'DEPOSIT', true)
                returning id
                """, Long.class, tenantId, accountId, Integer.toUnsignedLong(subject), namespace,
                accountId, accountId, "m/44'/60'/" + namespace + "'/" + subject + "/0");
        UUID custodyAddressId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, derivation_subject, derivation_child)
                values (?, ?, ?, 'ETH', 'qa', ?, ?, 'CONSOLE', ?, 0)
                """, custodyAddressId, tenantId, chainAddressId, accountId,
                "tenant-isolation-address-" + subject, subject);
        return custodyAddressId;
    }

    private void createChainAddress(UUID tenantId, String chain, String accountId) {
        int namespace = jdbc.queryForObject(
                "select derivation_namespace from custody_tenant where id = ?",
                Integer.class, tenantId);
        int subject = jdbc.queryForObject(
                "select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        jdbc.update("""
                insert into chain_address(
                    tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, owner_address, derivation_path,
                    wallet_role, enabled)
                values (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 'DEPOSIT', true)
                """, tenantId, chain, chain, accountId, Integer.toUnsignedLong(subject), namespace,
                accountId, accountId, "m/test/" + namespace + "/" + subject);
    }
}
