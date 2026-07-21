package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.service.chain.BlockchainAdapterRegistry;
import com.surprising.wallet.service.chain.tron.TronChainAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyOperationsIntegrationTest {
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
    void webhookAttemptsKeepAutomaticAndManualHistory() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            quiesceExistingWebhookQueue();
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
    void webhookDeliveriesCanBeFilteredAndFailedBatchRetryIsEndpointScoped() {
        transactions.executeWithoutResult(transaction -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            UUID tenantId = createTenant();
            UUID endpointId = insertWebhookEndpoint(tenantId, "Batch endpoint");
            UUID otherEndpointId = insertWebhookEndpoint(tenantId, "Other endpoint");
            insertWebhookDelivery(tenantId, endpointId, "FAILED");
            insertWebhookDelivery(tenantId, endpointId, "RETRY");
            insertWebhookDelivery(tenantId, endpointId, "DELIVERED");
            insertWebhookDelivery(tenantId, otherEndpointId, "FAILED");

            List<Map<String, Object>> failed = repository.listWebhookDeliveries(
                    tenantId, endpointId, "FAILED", 20, 0);
            assertEquals(1, failed.size());
            assertEquals("FAILED", failed.getFirst().get("status"));

            assertEquals(2, repository.retryFailedWebhookDeliveries(tenantId, endpointId));
            assertEquals(2, jdbc.queryForObject("""
                    select count(*) from custody_webhook_delivery
                     where tenant_id = ? and endpoint_id = ? and status = 'RETRY'
                       and next_attempt_trigger = 'MANUAL' and manual_retry_count = 1
                    """, Integer.class, tenantId, endpointId));
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from custody_webhook_delivery
                     where tenant_id = ? and endpoint_id = ? and status = 'DELIVERED'
                    """, Integer.class, tenantId, endpointId));
            assertEquals("FAILED", jdbc.queryForObject("""
                    select status from custody_webhook_delivery
                     where tenant_id = ? and endpoint_id = ?
                    """, String.class, tenantId, otherEndpointId));
            transaction.setRollbackOnly();
        });
    }

    @Test
    void recoveredWebhookLeaseFencesTheStaleWorker() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            quiesceExistingWebhookQueue();
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
                    "select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
            String accountId = "0x" + UUID.randomUUID().toString().replace("-", "")
                    + "12345678";
            Long chainAddressId = jdbc.queryForObject("""
                    insert into chain_address(
                        tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                        address, derivation_path, wallet_role, enabled)
                    values (?, 'ETH', 'ETH', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                    returning id
                    """, Long.class, tenantId, accountId, Integer.toUnsignedLong(subject), namespace,
                    accountId, "m/44'/60'/" + namespace + "'/" + subject + "/0");
            jdbc.update("""
                    insert into custody_address(
                        id, tenant_id, chain_address_id, chain, network, address,
                        subject, source, derivation_subject, derivation_child)
                    values (?, ?, ?, 'ETH', 'qa', ?, ?, 'CONSOLE', ?, 0)
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
                        tenant_id, chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values (?, 'ETH', 'ETH', ?, 2.5, 0.5, 3)
                    """, tenantId, accountId);

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
                    "select nextval('custody_derivation_subject_index_seq')::integer",
                    Integer.class);
            String accountId = "0x" + UUID.randomUUID().toString().replace("-", "")
                    + "12345678";
            Long chainAddressId = jdbc.queryForObject("""
                    insert into chain_address(
                        tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                        address, derivation_path, wallet_role, enabled)
                    values (?, 'ETH', 'ETH', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                    returning id
                    """, Long.class, tenantId, accountId, Integer.toUnsignedLong(subject), namespace,
                    accountId, "m/44'/60'/" + namespace + "'/" + subject + "/0");
            jdbc.update("""
                    insert into custody_address(
                        id, tenant_id, chain_address_id, chain, network, address,
                        subject, source, derivation_subject, derivation_child)
                    values (?, ?, ?, 'ETH', 'qa', ?, ?, 'CONSOLE', ?, 0)
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
                        tenant_id, chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values (?, 'ETH', 'ETH', ?, 1, 0, 1)
                    """, tenantId, accountId);
            String orderNo = "CW-QA-" + UUID.randomUUID().toString().substring(0, 8);
            String txHash = "0x" + UUID.randomUUID().toString().replace("-", "");
            jdbc.update("""
                    insert into withdrawal_order(
                        tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                        debit_account_id, to_address, amount, fee, status, tx_hash)
                    values (?, ?, ?, 'ETH', 'ETH', ?, ?, ?, 0.25, 0,
                            'CONFIRMED', ?)
                    """, tenantId, orderNo, Integer.toUnsignedLong(subject), accountId,
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
                    "select nextval('custody_derivation_subject_index_seq')::integer",
                    Integer.class);
            String accountId = "0x" + UUID.randomUUID().toString().replace("-", "")
                    + "12345678";
            Long chainAddressId = jdbc.queryForObject("""
                    insert into chain_address(
                        tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                        address, derivation_path, wallet_role, enabled)
                    values (?, 'ETH', 'ETH', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                    returning id
                    """, Long.class, tenantId, accountId, Integer.toUnsignedLong(subject), namespace,
                    accountId, "m/44'/60'/" + namespace + "'/" + subject + "/0");
            jdbc.update("""
                    insert into custody_address(
                        id, tenant_id, chain_address_id, chain, network, address,
                        subject, source, derivation_subject, derivation_child)
                    values (?, ?, ?, 'ETH', 'qa', ?, ?, 'CONSOLE', ?, 0)
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
                        tenant_id, chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values (?, 'ETH', 'ETH', ?, 0.1, 0, 0.1)
                    """, tenantId, accountId);
            String orderNo = "CW-OVERDUE-" + UUID.randomUUID().toString().substring(0, 8);
            jdbc.update("""
                    insert into withdrawal_order(
                        tenant_id, order_no, user_id, chain, asset_symbol,
                        from_address, debit_account_id, to_address, amount, fee, status)
                    values (?, ?, ?, 'ETH', 'ETH', ?, ?, ?, 0.25, 0, 'CONFIRMED')
                    """, tenantId, orderNo, Integer.toUnsignedLong(subject),
                    accountId, accountId, accountId);
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

    @Test
    void platformTenantManagementSupportsSearchDetailUpdateUnlockAndSessionRevocation() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            UUID tenantId = createTenant();
            UUID administratorId = UUID.randomUUID();
            String slug = jdbc.queryForObject(
                    "select slug from custody_tenant where id = ?",
                    String.class, tenantId);
            jdbc.update("""
                    insert into custody_tenant_user(
                        id, tenant_id, email, display_name, password_hash, role,
                        status, failed_login_count, locked_until)
                    values (?, ?, ?, 'Operations administrator', 'test-only-hash',
                            'TENANT_ADMIN', 'ACTIVE', 5, now() + interval '15 minutes')
                    """, administratorId, tenantId, slug + "@example.test");
            repository.insertSession(
                    UUID.randomUUID(),
                    administratorId,
                    tenantId,
                    "test-session-" + UUID.randomUUID(),
                    "127.0.0.1",
                    "integration-test",
                    Instant.now().plusSeconds(3600));

            assertEquals(1, repository.countTenants(slug, "ACTIVE"));
            List<Map<String, Object>> tenants =
                    repository.listTenants(slug, "ACTIVE", 20, 0);
            assertEquals(1, tenants.size());
            assertEquals(tenantId, tenants.getFirst().get("id"));

            Map<String, Object> statistics =
                    repository.tenantOperationsSummary(tenantId);
            assertEquals(1L, statistics.get("userCount"));
            assertEquals(1L, statistics.get("activeSessionCount"));
            assertTrue(repository.listWebhookDeliveries(tenantId, null, null, 20, 0).isEmpty());

            Map<String, Object> administrator =
                    repository.listTenantUsers(tenantId).getFirst();
            assertEquals(5, administrator.get("failedLoginCount"));
            assertTrue(administrator.get("lockedUntil") instanceof Instant);
            Map<String, Object> unlocked =
                    repository.unlockTenantAdministrator(tenantId, administratorId);
            assertEquals(0, unlocked.get("failedLoginCount"));
            assertNull(unlocked.get("lockedUntil"));

            repository.updateTenantProfile(tenantId, "Updated operations tenant", "SGD");
            var updated = repository.requireTenant(tenantId);
            assertEquals("Updated operations tenant", updated.name());
            assertEquals("SGD", updated.displayCurrency());

            assertEquals(1, repository.revokeTenantSessions(tenantId));
            assertEquals(0L,
                    repository.tenantOperationsSummary(tenantId).get("activeSessionCount"));
            status.setRollbackOnly();
        });
    }

    @Test
    void tenantChainMustBeOpenedBeforeOperationsAndCanBeClosedAgain() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository custody = new CustodyRepository(jdbc);
            CustodyTenantChainRepository chainRepository =
                    new CustodyTenantChainRepository(jdbc);
            CustodyTenantChainService service = new CustodyTenantChainService(
                    chainRepository, custody,
                    new BlockchainAdapterRegistry(List.of(new TronChainAdapter())));
            UUID tenantId = createTenant();
            UUID administratorId = UUID.randomUUID();
            String slug = jdbc.queryForObject(
                    "select slug from custody_tenant where id = ?", String.class, tenantId);
            jdbc.update("""
                    insert into custody_tenant_user(
                        id, tenant_id, email, display_name, password_hash, role, status)
                    values (?, ?, ?, 'Chain administrator', 'test-only-hash',
                            'TENANT_ADMIN', 'ACTIVE')
                    """, administratorId, tenantId, slug + "@example.test");
            CustodyPrincipal principal = new CustodyPrincipal(
                    CustodyPrincipal.ActorType.TENANT_USER,
                    administratorId, tenantId, slug, "TENANT_ADMIN", java.util.Set.of("*"));

            assertThrows(CustodyForbiddenException.class,
                    () -> service.requireActive(tenantId, "TRON"));
            var opened = service.setEnabled(principal, "TRON", true, "127.0.0.1");
            assertTrue(opened.enabled());
            service.requireActive(tenantId, "TRON");
            assertTrue(service.list(principal).stream()
                    .anyMatch(chain -> chain.chain().equals("TRON") && chain.enabled()));

            var closed = service.setEnabled(principal, "TRON", false, "127.0.0.1");
            assertFalse(closed.enabled());
            assertThrows(CustodyForbiddenException.class,
                    () -> service.requireActive(tenantId, "TRON"));
            assertEquals(2, jdbc.queryForObject("""
                    select count(*) from custody_audit_log
                     where tenant_id = ? and action in ('TENANT_CHAIN.OPEN', 'TENANT_CHAIN.CLOSE')
                    """, Integer.class, tenantId));
            status.setRollbackOnly();
        });
    }

    @Test
    void dashboardListsOpenedChainsBeforeTheyHaveAddressesOrBalances() {
        transactions.executeWithoutResult(status -> {
            UUID tenantId = createTenant();
            UUID administratorId = UUID.randomUUID();
            String slug = jdbc.queryForObject(
                    "select slug from custody_tenant where id = ?", String.class, tenantId);
            jdbc.update("""
                    insert into custody_tenant_user(
                        id, tenant_id, email, display_name, password_hash, role, status)
                    values (?, ?, ?, 'Asset administrator', 'test-only-hash',
                            'TENANT_ADMIN', 'ACTIVE')
                    """, administratorId, tenantId, slug + "@example.test");
            CustodyTenantChainRepository chainRepository =
                    new CustodyTenantChainRepository(jdbc);
            chainRepository.setStatus(tenantId, "TRON", "ACTIVE", administratorId);

            CustodyAssetDashboardService service = new CustodyAssetDashboardService(
                    new CustodyAssetDashboardRepository(jdbc), new CustodyRepository(jdbc),
                    chainRepository);
            CustodyPrincipal principal = new CustodyPrincipal(
                    CustodyPrincipal.ActorType.TENANT_USER,
                    administratorId, tenantId, slug, "TENANT_ADMIN", java.util.Set.of("assets:read"));

            var dashboard = service.dashboard(principal);
            assertTrue(dashboard.assets().isEmpty());
            assertEquals(1, dashboard.openedChains().size());
            var tron = dashboard.openedChains().getFirst();
            assertEquals("TRON", tron.chain());
            assertEquals("NOT_GENERATED", tron.status());
            assertNull(tron.collectionAddress());
            assertEquals(0, BigDecimal.ZERO.compareTo(tron.totalBalance()));
            status.setRollbackOnly();
        });
    }

    @Test
    void assetDashboardAggregatesStablecoinsAcrossChainsWithoutFloatingPointMath() {
        transactions.executeWithoutResult(status -> {
            UUID tenantId = createTenant();
            int namespace = jdbc.queryForObject(
                    "select derivation_namespace from custody_tenant where id = ?",
                    Integer.class, tenantId);
            String[] chains = {"TRON", "ARBITRUM"};
            BigDecimal[] totals = {new BigDecimal("5.25"), new BigDecimal("6.75")};
            for (int index = 0; index < chains.length; index++) {
                int subject = jdbc.queryForObject(
                        "select nextval('custody_derivation_subject_index_seq')::integer",
                        Integer.class);
                String accountId = "account-" + UUID.randomUUID();
                Long chainAddressId = jdbc.queryForObject("""
                        insert into chain_address(
                            tenant_id, chain, asset_symbol, account_id, user_id, biz,
                            address_index, address, derivation_path, wallet_role, enabled)
                        values (?, ?, 'USDT', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                        returning id
                        """, Long.class, tenantId, chains[index], accountId,
                        Integer.toUnsignedLong(subject), namespace, accountId,
                        "m/44'/60'/" + namespace + "'/" + subject + "/0");
                jdbc.update("""
                        insert into custody_address(
                            id, tenant_id, chain_address_id, chain, network, address,
                            subject, source, derivation_subject, derivation_child)
                        values (?, ?, ?, ?, 'qa', ?, ?, 'CONSOLE', ?, 0)
                        """, UUID.randomUUID(), tenantId, chainAddressId, chains[index],
                        accountId, "customer-" + index, subject);
                jdbc.update("""
                        insert into ledger_balance(
                            tenant_id, chain, asset_symbol, account_id,
                            available_balance, locked_balance, total_balance)
                        values (?, ?, 'USDT', ?, ?, 0, ?)
                        """, tenantId, chains[index], accountId, totals[index], totals[index]);
            }

            CustodyAssetDashboardService service = new CustodyAssetDashboardService(
                    new CustodyAssetDashboardRepository(jdbc), new CustodyRepository(jdbc),
                    new CustodyTenantChainRepository(jdbc));
            CustodyPrincipal principal = new CustodyPrincipal(
                    CustodyPrincipal.ActorType.TENANT_USER, UUID.randomUUID(), tenantId,
                    "dashboard-it", "VIEWER", java.util.Set.of("assets:read"));
            var dashboard = service.dashboard(principal);
            var usdt = dashboard.bySymbol().stream()
                    .filter(row -> row.assetSymbol().equals("USDT"))
                    .findFirst().orElseThrow();

            assertEquals(0, new BigDecimal("12.00").compareTo(usdt.totalBalance()));
            assertEquals(0, new BigDecimal("12.00").compareTo(usdt.valueUsd()));
            assertEquals(0, new BigDecimal("12.00").compareTo(dashboard.totalValueUsd()));
            assertEquals(List.of("ARBITRUM", "TRON"),
                    usdt.chains().stream().sorted().toList());
            assertEquals(0, dashboard.unpricedAssetCount());
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

    private UUID insertWebhookEndpoint(UUID tenantId, String name) {
        UUID endpointId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_webhook_endpoint(
                    id, tenant_id, name, url, secret_ciphertext,
                    subscribed_events, status, verified_at)
                values (?, ?, ?, ?, 'ciphertext',
                        array['DEPOSIT.CONFIRMED'], 'ACTIVE', now())
                """, endpointId, tenantId, name,
                "https://example.com/hooks/" + endpointId);
        return endpointId;
    }

    private UUID insertWebhookDelivery(UUID tenantId, UUID endpointId, String status) {
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_event(
                    id, tenant_id, event_type, aggregate_type, aggregate_id,
                    payload, status, published_at)
                values (?, ?, 'DEPOSIT.CONFIRMED', 'DEPOSIT', ?, '{}'::jsonb,
                        'PUBLISHED', now())
                """, eventId, tenantId, UUID.randomUUID().toString());
        jdbc.update("""
                insert into custody_webhook_delivery(
                    id, tenant_id, endpoint_id, event_id, status,
                    next_attempt_at, delivered_at)
                values (?, ?, ?, ?, ?, now(), case when ? = 'DELIVERED' then now() end)
                """, deliveryId, tenantId, endpointId, eventId, status, status);
        return deliveryId;
    }

    private void quiesceExistingWebhookQueue() {
        jdbc.update("""
                update custody_webhook_delivery
                   set status = 'DELIVERED',
                       delivered_at = coalesce(delivered_at, now()),
                       locked_by = null,
                       locked_at = null
                 where status in ('PENDING', 'RETRY', 'DELIVERING')
                """);
    }
}
