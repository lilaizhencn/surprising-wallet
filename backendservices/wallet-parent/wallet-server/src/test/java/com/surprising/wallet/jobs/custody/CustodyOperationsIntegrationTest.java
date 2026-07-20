package com.surprising.wallet.jobs.custody;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyOperationsIntegrationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv("SW_TEST_CUSTODY_DB_URL"));
        dataSource.setUsername(System.getenv().getOrDefault(
                "SW_TEST_CUSTODY_DB_USERNAME", "postgres"));
        dataSource.setPassword(System.getenv().getOrDefault(
                "SW_TEST_CUSTODY_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/custody-schema.sql"));
        }
    }

    @Test
    void webhookAttemptsKeepAutomaticAndManualHistory() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            UUID tenantId = createTenant();
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            jdbc.update("""
                    insert into custody_webhook_endpoint(
                        id, tenant_id, name, url, secret_ciphertext,
                        subscribed_events, status, verified_at)
                    values (?, ?, 'QA endpoint', 'https://example.com/hooks', 'ciphertext',
                            array['DEPOSIT.CONFIRMED'], 'ACTIVE', now())
                    """, endpointId, tenantId);
            jdbc.update("""
                    insert into custody_event(
                        id, tenant_id, event_type, aggregate_type, aggregate_id,
                        payload, status, published_at)
                    values (?, ?, 'DEPOSIT.CONFIRMED', 'DEPOSIT', ?, '{}'::jsonb,
                            'PUBLISHED', now())
                    """, eventId, tenantId, UUID.randomUUID().toString());
            jdbc.update("""
                    insert into custody_webhook_delivery(
                        id, tenant_id, endpoint_id, event_id)
                    values (?, ?, ?, ?)
                    """, deliveryId, tenantId, endpointId, eventId);

            var automatic = repository.claimWebhookDeliveries("qa-worker", 1).getFirst();
            assertEquals("AUTOMATIC", automatic.attemptTrigger());
            repository.markWebhookFailed(
                    automatic, 503, "unavailable", "", Instant.now().plusSeconds(60),
                    false, 25L);
            repository.retryWebhookDelivery(tenantId, deliveryId);
            var manual = repository.claimWebhookDeliveries("qa-worker", 1).getFirst();
            assertEquals("MANUAL", manual.attemptTrigger());
            repository.markWebhookDelivered(manual, 204, "", 12L);

            List<Map<String, Object>> attempts =
                    repository.listWebhookDeliveryAttempts(tenantId, deliveryId, 10, 0);
            assertEquals(2, attempts.size());
            assertEquals("MANUAL", attempts.getFirst().get("trigger"));
            assertEquals("DELIVERED", attempts.getFirst().get("status"));
            assertEquals("AUTOMATIC", attempts.get(1).get("trigger"));
            assertEquals("RETRY_SCHEDULED", attempts.get(1).get("status"));
            status.setRollbackOnly();
        });
    }

    @Test
    void recoveredWebhookLeaseFencesTheStaleWorker() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            UUID tenantId = createTenant();
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            jdbc.update("""
                    insert into custody_webhook_endpoint(
                        id, tenant_id, name, url, secret_ciphertext,
                        subscribed_events, status, verified_at)
                    values (?, ?, 'Recovery endpoint', 'https://example.com/hooks',
                            'ciphertext', array['DEPOSIT.CONFIRMED'], 'ACTIVE', now())
                    """, endpointId, tenantId);
            jdbc.update("""
                    insert into custody_event(
                        id, tenant_id, event_type, aggregate_type, aggregate_id,
                        payload, status, published_at)
                    values (?, ?, 'DEPOSIT.CONFIRMED', 'DEPOSIT', ?, '{}'::jsonb,
                            'PUBLISHED', now())
                    """, eventId, tenantId, UUID.randomUUID().toString());
            jdbc.update("""
                    insert into custody_webhook_delivery(
                        id, tenant_id, endpoint_id, event_id)
                    values (?, ?, ?, ?)
                    """, deliveryId, tenantId, endpointId, eventId);

            var stale = repository.claimWebhookDeliveries("worker-one", 1).getFirst();
            jdbc.update("""
                    update custody_webhook_delivery
                       set locked_at = now() - interval '6 minutes'
                     where id = ?
                    """, deliveryId);
            var recovered = repository.claimWebhookDeliveries("worker-two", 1).getFirst();
            assertEquals("RECOVERY", recovered.attemptTrigger());

            repository.markWebhookDelivered(stale, 200, "late success", 360_000L);
            assertEquals("DELIVERING", jdbc.queryForObject("""
                    select status from custody_webhook_delivery where id = ?
                    """, String.class, deliveryId));
            assertEquals("worker-two", jdbc.queryForObject("""
                    select locked_by from custody_webhook_delivery where id = ?
                    """, String.class, deliveryId));

            repository.markWebhookDelivered(recovered, 204, "", 20L);
            assertEquals("DELIVERED", jdbc.queryForObject("""
                    select status from custody_webhook_delivery where id = ?
                    """, String.class, deliveryId));
            List<Map<String, Object>> attempts =
                    repository.listWebhookDeliveryAttempts(tenantId, deliveryId, 10, 0);
            assertEquals(2, attempts.size());
            assertEquals("RECOVERY", attempts.getFirst().get("trigger"));
            assertEquals("DELIVERED", attempts.getFirst().get("status"));
            assertEquals("AUTOMATIC", attempts.get(1).get("trigger"));
            assertEquals("FAILED", attempts.get(1).get("status"));
            status.setRollbackOnly();
        });
    }

    @Test
    void gasAccountUsesTheRealNativeLedgerBalance() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            UUID tenantId = createTenant();
            UUID addressId = UUID.randomUUID();
            UUID gasAccountId = UUID.randomUUID();
            int namespace = jdbc.queryForObject(
                    "select nextval('custody_derivation_namespace_seq')::integer", Integer.class);
            int subject = jdbc.queryForObject(
                    "select nextval('custody_derivation_subject_seq')::integer", Integer.class);
            String accountId = "0x" + UUID.randomUUID().toString().replace("-", "")
                    + "12345678";
            Long chainAddressId = jdbc.queryForObject("""
                    insert into chain_address(
                        chain, asset_symbol, account_id, user_id, biz, address_index,
                        address, derivation_path, wallet_role, enabled)
                    values ('ETH', 'ETH', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                    returning id
                    """, Long.class, accountId, Integer.toUnsignedLong(subject), namespace,
                    accountId, "m/44'/60'/" + namespace + "'/" + subject + "/0");
            jdbc.update("""
                    insert into custody_address(
                        id, tenant_id, chain_address_id, chain, network, address,
                        external_reference, source, derivation_subject)
                    values (?, ?, ?, 'ETH', 'qa', ?, ?, 'CONSOLE', ?)
                    """, addressId, tenantId, chainAddressId, accountId,
                    "__sw_gas_reserve__:eth", subject);
            jdbc.update("""
                    insert into custody_gas_account(
                        id, tenant_id, custody_address_id, chain, network,
                        native_symbol, low_balance_threshold)
                    values (?, ?, ?, 'ETH', 'qa', 'ETH', 1)
                    """, gasAccountId, tenantId, addressId);
            jdbc.update("""
                    insert into ledger_balance(
                        chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values ('ETH', 'ETH', ?, 2.5, 0.5, 3)
                    """, accountId);

            var account = repository.requireGasAccount(tenantId, gasAccountId);
            assertEquals(0, new BigDecimal("2.5").compareTo(account.availableBalance()));
            assertEquals(0, new BigDecimal("3").compareTo(account.totalBalance()));
            assertFalse(account.lowBalance());
            assertTrue((Boolean) repository.onboardingStatus(tenantId).get("gasAccountFunded"));
            assertTrue(repository.listAddresses(
                    tenantId, "", "", "", "", 10, 0).isEmpty());
            assertTrue(repository.tenantAssetOverview(tenantId).isEmpty());
            status.setRollbackOnly();
        });
    }

    @Test
    void gasReservationSettlesConfirmedFeeAndPreservesAuditTrail() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            UUID tenantId = createTenant();
            UUID addressId = UUID.randomUUID();
            UUID gasAccountId = UUID.randomUUID();
            UUID withdrawalId = UUID.randomUUID();
            int namespace = jdbc.queryForObject(
                    "select derivation_namespace from custody_tenant where id = ?",
                    Integer.class, tenantId);
            int subject = jdbc.queryForObject(
                    "select nextval('custody_derivation_subject_seq')::integer",
                    Integer.class);
            String accountId = "0x" + UUID.randomUUID().toString().replace("-", "")
                    + "12345678";
            Long chainAddressId = jdbc.queryForObject("""
                    insert into chain_address(
                        chain, asset_symbol, account_id, user_id, biz, address_index,
                        address, derivation_path, wallet_role, enabled)
                    values ('ETH', 'ETH', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                    returning id
                    """, Long.class, accountId, Integer.toUnsignedLong(subject), namespace,
                    accountId, "m/44'/60'/" + namespace + "'/" + subject + "/0");
            jdbc.update("""
                    insert into custody_address(
                        id, tenant_id, chain_address_id, chain, network, address,
                        external_reference, source, derivation_subject)
                    values (?, ?, ?, 'ETH', 'qa', ?, ?, 'CONSOLE', ?)
                    """, addressId, tenantId, chainAddressId, accountId,
                    "__sw_gas_reserve__:eth", subject);
            jdbc.update("""
                    insert into custody_gas_account(
                        id, tenant_id, custody_address_id, chain, network,
                        native_symbol, low_balance_threshold)
                    values (?, ?, ?, 'ETH', 'qa', 'ETH', 0.1)
                    """, gasAccountId, tenantId, addressId);
            jdbc.update("""
                    insert into ledger_balance(
                        chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values ('ETH', 'ETH', ?, 1, 0, 1)
                    """, accountId);
            String orderNo = "CW-QA-" + UUID.randomUUID().toString().substring(0, 8);
            String txHash = "0x" + UUID.randomUUID().toString().replace("-", "");
            jdbc.update("""
                    insert into withdrawal_order(
                        order_no, user_id, chain, asset_symbol, from_address,
                        debit_account_id, to_address, amount, fee, status, tx_hash)
                    values (?, ?, 'ETH', 'ETH', ?, ?, ?, 0.25, 0,
                            'CONFIRMED', ?)
                    """, orderNo, Integer.toUnsignedLong(subject), accountId,
                    accountId, accountId, txHash);
            repository.insertCustodyWithdrawal(
                    withdrawalId, tenantId, addressId, orderNo, null, null,
                    "ETH", "ETH", accountId, new BigDecimal("0.25"),
                    BigDecimal.ZERO, "FROZEN", "CONSOLE", "qa");
            repository.reserveGasUsage(
                    tenantId, withdrawalId, orderNo, "ETH", new BigDecimal("0.1"));
            jdbc.update("""
                    insert into evm_tx(
                        chain, tx_hash, from_address, to_address, asset_symbol,
                        amount, fee, confirmations, status)
                    values ('ETH', ?, ?, ?, 'ETH', 0.25, 0.04, 12, 'CONFIRMED')
                    """, txHash, accountId, accountId);

            var change = repository.findWithdrawalStatusChanges(10).stream()
                    .filter(item -> item.id().equals(withdrawalId))
                    .findFirst().orElseThrow();
            assertTrue(repository.applyWithdrawalStatusChange(
                    change, UUID.randomUUID(), "WITHDRAWAL.CONFIRMED", "{}"));

            var usage = repository.findGasUsage(withdrawalId).orElseThrow();
            assertEquals("SETTLED", usage.status());
            assertEquals(0, new BigDecimal("0.04").compareTo(usage.actualAmount()));
            var account = repository.requireGasAccount(tenantId, gasAccountId);
            assertEquals(0, new BigDecimal("0.96").compareTo(account.availableBalance()));
            assertEquals(0, BigDecimal.ZERO.compareTo(account.lockedBalance()));
            assertEquals(0, new BigDecimal("0.96").compareTo(account.totalBalance()));
            assertEquals(1, repository.listGasUsage(tenantId, gasAccountId, 10, 0).size());
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from custody_ledger_entry
                     where tenant_id = ? and entry_type = 'NETWORK_FEE'
                    """, Integer.class, tenantId));
            status.setRollbackOnly();
        });
    }

    @Test
    void overdueGasSettlementRecoversAfterTopUpAndRemainsIdempotent() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            UUID tenantId = createTenant();
            UUID addressId = UUID.randomUUID();
            UUID gasAccountId = UUID.randomUUID();
            UUID withdrawalId = UUID.randomUUID();
            int namespace = jdbc.queryForObject(
                    "select derivation_namespace from custody_tenant where id = ?",
                    Integer.class, tenantId);
            int subject = jdbc.queryForObject(
                    "select nextval('custody_derivation_subject_seq')::integer",
                    Integer.class);
            String accountId = "0x" + UUID.randomUUID().toString().replace("-", "")
                    + "12345678";
            Long chainAddressId = jdbc.queryForObject("""
                    insert into chain_address(
                        chain, asset_symbol, account_id, user_id, biz, address_index,
                        address, derivation_path, wallet_role, enabled)
                    values ('ETH', 'ETH', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                    returning id
                    """, Long.class, accountId, Integer.toUnsignedLong(subject), namespace,
                    accountId, "m/44'/60'/" + namespace + "'/" + subject + "/0");
            jdbc.update("""
                    insert into custody_address(
                        id, tenant_id, chain_address_id, chain, network, address,
                        external_reference, source, derivation_subject)
                    values (?, ?, ?, 'ETH', 'qa', ?, ?, 'CONSOLE', ?)
                    """, addressId, tenantId, chainAddressId, accountId,
                    "__sw_gas_reserve__:eth", subject);
            jdbc.update("""
                    insert into custody_gas_account(
                        id, tenant_id, custody_address_id, chain, network,
                        native_symbol, low_balance_threshold)
                    values (?, ?, ?, 'ETH', 'qa', 'ETH', 0.1)
                    """, gasAccountId, tenantId, addressId);
            jdbc.update("""
                    insert into ledger_balance(
                        chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values ('ETH', 'ETH', ?, 0.1, 0, 0.1)
                    """, accountId);
            String orderNo = "CW-OVERDUE-" + UUID.randomUUID().toString().substring(0, 8);
            repository.insertCustodyWithdrawal(
                    withdrawalId, tenantId, addressId, orderNo, null, null,
                    "ETH", "ETH", accountId, new BigDecimal("0.25"),
                    BigDecimal.ZERO, "CONFIRMED", "CONSOLE", "qa");
            repository.reserveGasUsage(
                    tenantId, withdrawalId, orderNo, "ETH", new BigDecimal("0.1"));

            var overdue = repository.settleGasUsage(
                    withdrawalId, new BigDecimal("0.15"),
                    "CHAIN_RECORDED", "0xoverdue");
            assertEquals("OVERDUE", overdue.status());
            assertNull(overdue.settledAt());

            jdbc.update("""
                    update ledger_balance
                       set available_balance = available_balance + 0.1,
                           total_balance = total_balance + 0.1
                     where chain = 'ETH' and asset_symbol = 'ETH'
                       and lower(account_id) = lower(?)
                    """, accountId);
            var settled = repository.settleGasUsage(
                    withdrawalId, overdue.actualAmount(),
                    overdue.pricingSource(), overdue.txHash());
            assertEquals("SETTLED", settled.status());
            var account = repository.requireGasAccount(tenantId, gasAccountId);
            assertEquals(0, new BigDecimal("0.05").compareTo(account.availableBalance()));
            assertEquals(0, BigDecimal.ZERO.compareTo(account.lockedBalance()));
            assertEquals(0, new BigDecimal("0.05").compareTo(account.totalBalance()));

            repository.settleGasUsage(
                    withdrawalId, overdue.actualAmount(),
                    overdue.pricingSource(), overdue.txHash());
            var unchanged = repository.requireGasAccount(tenantId, gasAccountId);
            assertEquals(0, new BigDecimal("0.05").compareTo(unchanged.availableBalance()));
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from custody_ledger_entry
                     where tenant_id = ? and entry_type = 'NETWORK_FEE'
                    """, Integer.class, tenantId));
            status.setRollbackOnly();
        });
    }

    private UUID createTenant() {
        UUID tenantId = UUID.randomUUID();
        int namespace = jdbc.queryForObject(
                "select nextval('custody_derivation_namespace_seq')::integer", Integer.class);
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, ?, 'Custody operations integration test', ?)
                """, tenantId, "operations-it-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 16), namespace);
        return tenantId;
    }
}
