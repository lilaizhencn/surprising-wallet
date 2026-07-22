package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
