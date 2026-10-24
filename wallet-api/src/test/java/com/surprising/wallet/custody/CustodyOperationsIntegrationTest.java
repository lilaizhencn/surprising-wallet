package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAssetDashboardRepository;
import com.surprising.wallet.repository.CustodyRepository;
import com.surprising.wallet.repository.CustodyTenantChainRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.service.CustodyAddressService;
import com.surprising.wallet.service.CustodyAssetDashboardService;
import com.surprising.wallet.service.CustodyTenantChainService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code CustodyOperationsIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyOperationsIntegrationTest {
    /**
     * 保存 {@code jdbc}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private JdbcTemplate jdbc;
    private com.surprising.wallet.service.CustodyWebhookService webhookLifecycle;
    private com.surprising.wallet.service.CustodyTransactionQueryService history;
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
        webhookLifecycle = com.surprising.wallet.testutil.CustodyTestSupport.webhooks(jdbc);
        history = new com.surprising.wallet.service.CustodyTransactionQueryService(
                new com.surprising.wallet.repository.CustodyDepositRepository(jdbc),
                new com.surprising.wallet.repository.CustodyWithdrawalRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.WithdrawalOrderRepository(jdbc));
    }

    /**
     * 验证 {@code tenantLoginLookupUsesGloballyUniqueEmail} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tenantLoginLookupUsesGloballyUniqueEmail() {
        transactions.executeWithoutResult(status -> {
            var users = new com.surprising.wallet.repository.CustodyTenantUserRepository(jdbc);
            UUID firstTenantId = createTenant();
            UUID secondTenantId = createTenant();
            String email = "shared-login@example.test";
            jdbc.update("""
                    insert into custody_tenant_user(
                        id, tenant_id, email, display_name, password_hash, role, status)
                    values (?, ?, ?, 'First administrator', 'test-only-hash',
                            'TENANT_ADMIN', 'ACTIVE')
                    """, UUID.randomUUID(), firstTenantId, email);

            var user = users.findByEmail(email.toUpperCase(java.util.Locale.ROOT), false).orElseThrow();
            assertEquals(firstTenantId, user.tenantId());
            assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
                    insert into custody_tenant_user(
                        id, tenant_id, email, display_name, password_hash, role, status)
                    values (?, ?, ?, 'Second administrator', 'test-only-hash',
                            'TENANT_ADMIN', 'ACTIVE')
                    """, UUID.randomUUID(), secondTenantId, email.toUpperCase(java.util.Locale.ROOT)));
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code webhookAttemptsKeepAutomaticAndManualHistory} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void webhookAttemptsKeepAutomaticAndManualHistory() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            quiesceExistingWebhookQueue();
            UUID tenantId = createTenant();
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            jdbc.update("""
                    insert into custody_webhook_endpoint(
                        id, tenant_id, name, url, secret_ciphertext, status, verified_at)
                    values (?, ?, 'QA endpoint', 'https://example.com/hooks', 'ciphertext',
                            'ACTIVE', now())
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

            var automatic = webhookLifecycle.claimWebhookDeliveries("qa-worker", 1).getFirst();
            assertEquals("AUTOMATIC", automatic.attemptTrigger());
            webhookLifecycle.markWebhookFailed(
                    automatic, 503, "unavailable", "", Instant.now().plusSeconds(60),
                    false, 25L);
            new com.surprising.wallet.repository.CustodyWebhookDeliveryRepository(jdbc).retry(tenantId, deliveryId);
            var manual = webhookLifecycle.claimWebhookDeliveries("qa-worker", 1).getFirst();
            assertEquals("MANUAL", manual.attemptTrigger());
            webhookLifecycle.markWebhookDelivered(manual, 204, "", 12L);

            var attempts =
                    new com.surprising.wallet.repository.CustodyWebhookDeliveryAttemptRepository(jdbc).list(tenantId, deliveryId, 10, 0);
            assertEquals(2, attempts.size());
            assertEquals("MANUAL", attempts.getFirst().trigger());
            assertEquals("DELIVERED", attempts.getFirst().status());
            assertEquals("AUTOMATIC", attempts.get(1).trigger());
            assertEquals("RETRY_SCHEDULED", attempts.get(1).status());
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code webhookDeliveriesCanBeFilteredAndFailedBatchRetryIsEndpointScoped} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void webhookDeliveriesCanBeFilteredAndFailedBatchRetryIsEndpointScoped() {
        transactions.executeWithoutResult(transaction -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            UUID tenantId = createTenant();
            UUID endpointId = insertWebhookEndpoint(tenantId, "Batch endpoint");
            UUID otherEndpointId = insertWebhookEndpoint(tenantId, "Other endpoint");
            insertWebhookDelivery(tenantId, endpointId, "FAILED");
            insertWebhookDelivery(tenantId, endpointId, "RETRY");
            insertWebhookDelivery(tenantId, endpointId, "DELIVERED");
            insertWebhookDelivery(tenantId, otherEndpointId, "FAILED");

            var failed = new com.surprising.wallet.repository.CustodyWebhookDeliveryRepository(jdbc).list(
                    tenantId, endpointId, "FAILED", 20, 0);
            assertEquals(1, failed.size());
            assertEquals("FAILED", failed.getFirst().status());
            assertEquals(4, new com.surprising.wallet.repository.CustodyWebhookDeliveryRepository(jdbc).list(tenantId, null, null, 20, 0).size());

            assertEquals(2, new com.surprising.wallet.repository.CustodyWebhookDeliveryRepository(jdbc).retryFailed(tenantId, endpointId));
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

    /**
     * 验证 {@code recoveredWebhookLeaseFencesTheStaleWorker} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void recoveredWebhookLeaseFencesTheStaleWorker() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            quiesceExistingWebhookQueue();
            UUID tenantId = createTenant();
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            jdbc.update("""
                    insert into custody_webhook_endpoint(
                        id, tenant_id, name, url, secret_ciphertext, status, verified_at)
                    values (?, ?, 'Recovery endpoint', 'https://example.com/hooks',
                            'ciphertext', 'ACTIVE', now())
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

            var stale = webhookLifecycle.claimWebhookDeliveries("worker-one", 1).getFirst();
            jdbc.update("""
                    update custody_webhook_delivery
                       set locked_at = now() - interval '6 minutes'
                     where id = ?
                    """, deliveryId);
            var recovered = webhookLifecycle.claimWebhookDeliveries("worker-two", 1).getFirst();
            assertEquals("RECOVERY", recovered.attemptTrigger());

            webhookLifecycle.markWebhookDelivered(stale, 200, "late success", 360_000L);
            assertEquals("DELIVERING", jdbc.queryForObject("""
                    select status from custody_webhook_delivery where id = ?
                    """, String.class, deliveryId));
            assertEquals("worker-two", jdbc.queryForObject("""
                    select locked_by from custody_webhook_delivery where id = ?
                    """, String.class, deliveryId));

            webhookLifecycle.markWebhookDelivered(recovered, 204, "", 20L);
            assertEquals("DELIVERED", jdbc.queryForObject("""
                    select status from custody_webhook_delivery where id = ?
                    """, String.class, deliveryId));
            var attempts =
                    new com.surprising.wallet.repository.CustodyWebhookDeliveryAttemptRepository(jdbc).list(tenantId, deliveryId, 10, 0);
            assertEquals(2, attempts.size());
            assertEquals("RECOVERY", attempts.getFirst().trigger());
            assertEquals("DELIVERED", attempts.getFirst().status());
            assertEquals("AUTOMATIC", attempts.get(1).trigger());
            assertEquals("FAILED", attempts.get(1).status());
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code gasAccountUsesTheRealNativeLedgerBalance} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void gasAccountUsesTheRealNativeLedgerBalance() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
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
            assertTrue(com.surprising.wallet.testutil.CustodyTestSupport.addresses(jdbc, repository,
                    Mockito.mock(BlockchainRuntimeService.class),
                    Mockito.mock(CustodyTenantChainService.class)).list(
                    new CustodyPrincipal(CustodyPrincipal.ActorType.API_KEY, UUID.randomUUID(), tenantId,
                            "gas-address-test", "API_KEY", Set.of("addresses:read")), "", "", "", "", 10, 0).isEmpty());
            assertTrue(repository.tenantAssetOverview(tenantId).isEmpty());
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code transferListsReturnFullAddressesAndApplyTenantScopedFilters} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void transferListsReturnFullAddressesAndApplyTenantScopedFilters() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            UUID tenantId = createTenant();
            UUID otherTenantId = createTenant();
            TestAddress address = insertTestAddress(
                    tenantId, "ETH", "USDT", "customer-alpha",
                    "0x1111111111111111111111111111111111111111");
            TestAddress otherAddress = insertTestAddress(
                    otherTenantId, "ETH", "USDT", "customer-alpha-other-tenant",
                    "0x9999999999999999999999999999999999999999");
            String depositHash = "0xdeposit-filter-alpha";
            insertTestDeposit(tenantId, address, "USDT", depositHash, "CONFIRMED");
            insertTestDeposit(
                    otherTenantId, otherAddress, "USDT", depositHash + "-other", "CONFIRMED");

            var deposits = history.deposits(
                    historyPrincipal(tenantId), "ETH", "USDT", "CONFIRMED", "customer-alpha", 20, 0);
            assertEquals(1, deposits.size());
            assertEquals(address.address(), deposits.getFirst().address());
            assertEquals(depositHash, deposits.getFirst().txHash());
            assertTrue(history.deposits(
                    historyPrincipal(tenantId), "BTC", "USDT", "CONFIRMED", "customer-alpha", 20, 0).isEmpty());

            String orderNo = "CW-FILTER-ALPHA";
            String destination = "0x2222222222222222222222222222222222222222";
            String withdrawalHash = "0xwithdrawal-filter-alpha";
            insertTestWithdrawal(
                    repository, tenantId, address, orderNo, "payout-alpha",
                    destination, withdrawalHash, "CONFIRMED");
            insertTestWithdrawal(
                    repository, otherTenantId, otherAddress, orderNo + "-OTHER",
                    "payout-alpha-other", destination, withdrawalHash + "-other", "CONFIRMED");

            var withdrawals = history.withdrawals(
                    historyPrincipal(tenantId), "ETH", "USDT", "CONFIRMED", "payout-alpha", 20, 0);
            assertEquals(1, withdrawals.size());
            assertEquals(destination, withdrawals.getFirst().toAddress());
            assertEquals("customer-alpha", withdrawals.getFirst().subject());
            assertTrue(history.withdrawals(
                    historyPrincipal(tenantId), "ETH", "BTC", "CONFIRMED", destination, 20, 0).isEmpty());
            status.setRollbackOnly();
        });
    }

    @Test
    void transferHistoryCountsAndSearchesBeyondFiveHundredRecords() {
        transactions.executeWithoutResult(transaction -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            UUID tenantId = createTenant();
            TestAddress address = insertTestAddress(tenantId, "ETH", "USDT", "history-customer",
                    "0x7777777777777777777777777777777777777777");
            String prefix = UUID.randomUUID().toString();
            for (int index = 0; index < 620; index++) {
                insertTestDeposit(tenantId, address, "USDT", "history-deposit-" + index, "CONFIRMED");
                insertTestWithdrawal(repository, tenantId, address, prefix + "-" + index,
                        "history-payout-" + index, "0xrecipient", "history-transaction-" + index, "CONFIRMED");
            }
            jdbc.update("""
                    update custody_deposit set created_at = now() -
                        split_part(tx_hash, '-', 3)::int * interval '1 second' where tenant_id = ?
                    """, tenantId);
            jdbc.update("""
                    update custody_withdrawal set created_at = now() -
                        split_part(external_reference, '-', 3)::int * interval '1 second' where tenant_id = ?
                    """, tenantId);

            var summary = repository.tenantOperationsSummary(tenantId);
            org.junit.jupiter.api.Assertions.assertAll(

                    () -> assertEquals(620L, summary.get("depositCount")),
                    () -> assertEquals(620L, summary.get("withdrawalCount")),
                    () -> assertEquals(620, history.depositPage(historyPrincipal(tenantId), "", "", "", "", 20, 0).total()),
                    () -> assertEquals(620, history.withdrawalPage(historyPrincipal(tenantId), "", "", "", "", 20, 0).total()),
                    () -> assertEquals(20, history.deposits(historyPrincipal(tenantId), "", "", "", "", 20, 600).size()),
                    () -> assertEquals(20, history.withdrawals(historyPrincipal(tenantId), "", "", "", "", 20, 600).size()),
                    () -> assertEquals(1, history.deposits(historyPrincipal(tenantId), "", "", "", "history-deposit-619", 20, 0).size()),
                    () -> assertEquals(1, history.withdrawals(historyPrincipal(tenantId), "", "", "", "history-transaction-619", 20, 0).size()));
            transaction.setRollbackOnly();
        });
    }

    @Test
    void transferHistorySearchIsLiteralAndTenantScoped() {
        transactions.executeWithoutResult(transaction -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            UUID tenantId = createTenant();
            UUID otherTenantId = createTenant();
            var address = insertTestAddress(tenantId, "ETH", "USDT", "customer%_one", "0xown-address");
            var other = insertTestAddress(otherTenantId, "ETH", "USDT", "foreign-only", "0xforeign-address");
            insertTestDeposit(tenantId, address, "USDT", "deposit%_own", "CONFIRMED");
            insertTestDeposit(otherTenantId, other, "USDT", "deposit%_foreign", "CONFIRMED");
            insertTestWithdrawal(repository, tenantId, address, "QUERY-OWN", "payout%_one",
                    "recipient%_one", "transaction%_own", "SENT");
            insertTestWithdrawal(repository, otherTenantId, other, "QUERY-FOREIGN", "foreign-only",
                    "foreign-only", "transaction%_foreign", "SENT");
            var principal = historyPrincipal(tenantId);
            for (String search : List.of("CUSTOMER%_ONE", "0xown-address", "%_")) {
                assertEquals(1, history.depositPage(principal, "eth", "usdt", "confirmed", search, 20, 0).total());
            }
            assertEquals(0, history.depositPage(principal, "", "", "", "foreign-only", 20, 0).total());
            assertEquals(0, history.depositPage(principal, "", "", "REORGED", "", 20, 0).total());
            for (String search : List.of("QUERY-OWN", "payout%_one", "recipient%_one", "TRANSACTION%_OWN", "%_")) {
                var page = history.withdrawalPage(principal, "eth", "usdt", "sent", search, 20, 0);
                assertEquals(1, page.total(), search);
                assertEquals("transaction%_own", page.items().getFirst().txHash());
                assertEquals("customer%_one", page.items().getFirst().subject());
            }
            assertEquals(0, history.withdrawalPage(principal, "", "", "", "transaction%_foreign", 20, 0).total());
            var empty = history.withdrawalPage(principal, "", "", "", "", 1000, 1000);
            assertEquals(1, empty.total());
            assertEquals(200, empty.limit());
            assertTrue(empty.items().isEmpty());
            var mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
            var json = mapper.valueToTree(history.deposits(principal, "", "", "", "", 20, 0).getFirst());
            assertEquals("deposit%_own", json.path("txHash").asText());
            assertEquals("0xown-address", json.path("address").asText());
            assertFalse(json.has("tx_hash"));
            transaction.setRollbackOnly();
        });
    }

    @Test
    void transferHistoryRequiresTheMatchingTenantScope() {
        var noScopes = new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER, UUID.randomUUID(),
                UUID.randomUUID(), "history-test", "VIEWER", Set.of());
        var depositOnly = new CustodyPrincipal(CustodyPrincipal.ActorType.API_KEY, UUID.randomUUID(),
                noScopes.tenantId(), "history-test", "VIEWER", Set.of("deposits:read"));
        assertThrows(CustodyForbiddenException.class,
                () -> history.depositPage(noScopes, "", "", "", "", 20, 0));
        assertThrows(CustodyForbiddenException.class,
                () -> history.withdrawals(depositOnly, "", "", "", "", 20, 0));
        assertThrows(CustodyForbiddenException.class,
                () -> history.deposits((CustodyPrincipal) null, "", "", "", "", 20, 0));
    }

    @Test
    void gasReservationUsesTenantGasAccountAndPreservesAuditTrail() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
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
            UUID customerAddressId = UUID.randomUUID();
            String customerAccountId = "0x" + UUID.randomUUID().toString().replace("-", "")
                    + "87654321";
            Long customerChainAddressId = jdbc.queryForObject("""
                    insert into chain_address(
                        tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                        address, derivation_path, wallet_role, enabled)
                    values (?, 'ETH', 'ETH', ?, ?, ?, 1, ?, ?, 'DEPOSIT', true)
                    returning id
                    """, Long.class, tenantId, customerAccountId,
                    Integer.toUnsignedLong(subject + 1), namespace, customerAccountId,
                    "m/44'/60'/" + namespace + "'/" + (subject + 1) + "/1");
            jdbc.update("""
                    insert into custody_address(
                        id, tenant_id, chain_address_id, chain, network, address,
                        subject, source, derivation_subject, derivation_child)
                    values (?, ?, ?, 'ETH', 'qa', ?, 'customer-without-eth',
                            'CONSOLE', ?, 1)
                    """, customerAddressId, tenantId, customerChainAddressId,
                    customerAccountId, subject + 1);
            jdbc.update("""
                    insert into ledger_balance(
                        tenant_id, chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values (?, 'ETH', 'USDT', ?, 0.75, 0, 0.75)
                    """, tenantId, customerAccountId);
            String orderNo = "CW-QA-" + UUID.randomUUID().toString().substring(0, 8);
            String txHash = "0x" + UUID.randomUUID().toString().replace("-", "");
            jdbc.update("""
                    insert into withdrawal_order(
                        tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                        debit_account_id, to_address, amount, fee, status, tx_hash)
                    values (?, ?, ?, 'ETH', 'USDT', ?, ?, ?, 0.25, 0,
                            'CONFIRMED', ?)
                    """, tenantId, orderNo, Integer.toUnsignedLong(subject), accountId,
                    customerAccountId, accountId, txHash);
            repository.insertCustodyWithdrawal(
                    withdrawalId, tenantId, customerAddressId, orderNo, null, null,
                    "ETH", "USDT", accountId, new BigDecimal("0.25"),
                    BigDecimal.ZERO, "FROZEN", "CONSOLE", "qa");
            repository.reserveGasUsage(
                    tenantId, withdrawalId, orderNo, "ETH", new BigDecimal("0.1"));
            jdbc.update("""
                    insert into evm_tx(
                        chain, tx_hash, from_address, to_address, asset_symbol,
                        amount, fee, confirmations, status)
                    values ('ETH', ?, ?, ?, 'USDT', 0.25, 0.04, 12, 'CONFIRMED')
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
            assertEquals(1, new com.surprising.wallet.repository.CustodyGasUsageRepository(jdbc).listForAccount(
                    tenantId,
                    gasAccountId,
                    10,
                    0).size());
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from custody_ledger_entry
                     where tenant_id = ? and entry_type = 'NETWORK_FEE'
                    """, Integer.class, tenantId));
            assertEquals(accountId, jdbc.queryForObject("""
                    select account_id from custody_ledger_entry
                     where tenant_id = ? and entry_type = 'NETWORK_FEE'
                    """, String.class, tenantId));
            assertEquals("ETH", jdbc.queryForObject("""
                    select asset_symbol from custody_ledger_entry
                     where tenant_id = ? and entry_type = 'NETWORK_FEE'
                    """, String.class, tenantId));
            assertEquals(addressId, jdbc.queryForObject("""
                    select custody_address_id from custody_ledger_entry
                     where tenant_id = ? and entry_type = 'NETWORK_FEE'
                    """, UUID.class, tenantId));
            status.setRollbackOnly();
        });
    }

    @Test
    void failedWithdrawalProjectionDoesNotReleaseCustomerBalanceTwice() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            UUID tenantId = createTenant();
            TestAddress address = insertTestAddress(
                    tenantId, "ETH", "ETH", "failed-withdrawal-customer", "0x"
                            + (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                                    .replace("-", "").substring(0, 40));
            String orderNo = "CW-FAILED-" + UUID.randomUUID().toString().substring(0, 8);
            jdbc.update("""
                    insert into ledger_balance(
                        tenant_id, chain, asset_symbol, account_id,
                        available_balance, locked_balance, total_balance)
                    values (?, 'ETH', 'USDT', ?, 0.25, 0, 0.25)
                    """, tenantId, address.address());
            jdbc.update("""
                    insert into withdrawal_order(
                        tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                        debit_account_id, to_address, amount, fee, status, error_message)
                    values (?, ?, ?, 'ETH', 'USDT', ?, ?, ?, 0.25, 0,
                            'FAILED', 'test failure after chain workflow released the lock')
                    """, tenantId, orderNo, address.userId(), address.address(),
                    address.address(), "0x" + (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                            .replace("-", "").substring(0, 40));
            UUID withdrawalId = UUID.randomUUID();
            repository.insertCustodyWithdrawal(withdrawalId, tenantId, address.id(), orderNo,
                    "failed-withdrawal-ref", null, "ETH", "USDT", "0x" + UUID.randomUUID()
                            .toString().replace("-", "") + "12345678",
                    new BigDecimal("0.25"), BigDecimal.ZERO, "SIGNING", "API_KEY", "qa");

            var change = repository.findWithdrawalStatusChanges(10).stream()
                    .filter(item -> item.id().equals(withdrawalId))
                    .findFirst().orElseThrow();
            assertTrue(repository.applyWithdrawalStatusChange(
                    change, UUID.randomUUID(), "WITHDRAWAL.FAILED", "{}"));

            assertEquals("FAILED", jdbc.queryForObject(
                    "select status from custody_withdrawal where id = ?", String.class, withdrawalId));
            assertEquals(0, new BigDecimal("0.25").compareTo(jdbc.queryForObject("""
                    select available_balance from ledger_balance
                     where tenant_id = ? and chain = 'ETH' and asset_symbol = 'USDT' and account_id = ?
                    """, BigDecimal.class, tenantId, address.address())));
            assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject("""
                    select locked_balance from ledger_balance
                     where tenant_id = ? and chain = 'ETH' and asset_symbol = 'USDT' and account_id = ?
                    """, BigDecimal.class, tenantId, address.address())));
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code overdueGasSettlementRecoversAfterTopUpAndRemainsIdempotent} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void overdueGasSettlementRecoversAfterTopUpAndRemainsIdempotent() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
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

    /**
     * 验证 {@code platformTenantManagementSupportsSearchDetailUpdateUnlockAndSessionRevocation} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void platformTenantManagementSupportsSearchDetailUpdateUnlockAndSessionRevocation() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
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
            new com.surprising.wallet.repository.CustodySessionRepository(jdbc).insert(
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
            assertTrue(new com.surprising.wallet.repository.CustodyWebhookDeliveryRepository(jdbc).list(tenantId, null, null, 20, 0).isEmpty());

            var users = new com.surprising.wallet.repository.CustodyTenantUserRepository(jdbc);
            var administrator = users.listByTenant(tenantId).getFirst();
            assertEquals(5, administrator.failedLoginCount());
            assertTrue(administrator.lockedUntil().isAfter(Instant.now()));
            assertEquals(1, users.unlockAdministrator(tenantId, administratorId));
            var unlocked = users.findView(tenantId, administratorId).orElseThrow();
            assertEquals(0, unlocked.failedLoginCount());
            assertNull(unlocked.lockedUntil());

            repository.updateTenantProfile(tenantId, "Updated operations tenant", "SGD");
            var updated = repository.requireTenant(tenantId);
            assertEquals("Updated operations tenant", updated.name());
            assertEquals("SGD", updated.displayCurrency());

            assertEquals(1, new com.surprising.wallet.repository.CustodySessionRepository(jdbc).revokeByTenant(tenantId));
            assertEquals(0L,
                    repository.tenantOperationsSummary(tenantId).get("activeSessionCount"));
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code tenantChainMustBeOpenedBeforeOperationsAndCanBeClosedAgain} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void everyConfiguredChainIsVisibleAndFormerlyMissingAdaptersCanBeOpened() {
        transactions.executeWithoutResult(status -> {
            // Exercise all implemented chains without changing the platform's default network policy.
            List<Long> networks = jdbc.queryForList("""
                    select distinct on (chain) id from chain_profile
                    order by chain, enabled desc, id
                    """, Long.class);
            jdbc.update("update chain_profile set enabled = false");
            networks.forEach(id -> jdbc.update("update chain_profile set enabled = true where id = ?", id));
            UUID tenantId = createTenant();
            UUID administratorId = UUID.randomUUID();
            String slug = jdbc.queryForObject("select slug from custody_tenant where id = ?", String.class, tenantId);
            jdbc.update("""
                    insert into custody_tenant_user(id, tenant_id, email, display_name, password_hash, role, status)
                    values (?, ?, ?, 'Chain administrator', 'test-only-hash', 'TENANT_ADMIN', 'ACTIVE')
                    """, administratorId, tenantId, slug + "@example.test");
            var principal = new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,
                    administratorId, tenantId, slug, "TENANT_ADMIN", Set.of("*"));
            var chains = new CustodyTenantChainRepository(jdbc);
            var runtime = new BlockchainRuntimeService(new com.surprising.wallet.repository.ChainProfileRepository(jdbc),
                            new com.surprising.wallet.repository.ChainAssetRepository(jdbc), null, null);
            var service = new CustodyTenantChainService(chains, com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc), runtime);
            Set<String> expected = java.util.Arrays.stream(ChainType.values())
                    .map(Enum::name).collect(java.util.stream.Collectors.toSet());
            assertEquals(expected, service.list(principal).stream()
                    .map(CustodyTenantChainService.ChainView::chain).collect(java.util.stream.Collectors.toSet()));
            for (String chain : List.of("ADA", "DOT", "HYPERCORE", "NEAR", "XMR")) {
                assertTrue(service.setEnabled(principal, chain, true, "127.0.0.1").enabled(), chain);
                service.requireActive(tenantId, chain);
                assertFalse(service.setEnabled(principal, chain, false, "127.0.0.1").enabled(), chain);
                assertThrows(CustodyForbiddenException.class, () -> service.requireActive(tenantId, chain));
            }
            status.setRollbackOnly();
        });
    }

    @Test
    void tenantChainMustBeOpenedBeforeOperationsAndCanBeClosedAgain() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository custody = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            CustodyTenantChainRepository chainRepository =
                    new CustodyTenantChainRepository(jdbc);
            CustodyTenantChainService service = new CustodyTenantChainService(
                    chainRepository, custody,
                    new BlockchainRuntimeService(new com.surprising.wallet.repository.ChainProfileRepository(jdbc),
                            new com.surprising.wallet.repository.ChainAssetRepository(jdbc), null, null));
            jdbc.update("""
                    update chain_profile
                       set scan_enabled = true,
                           withdraw_enabled = true,
                           transfer_enabled = true
                     where chain = 'TRON' and enabled = true
                    """);
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
            assertFalse(chainRepository.depositEnabled(tenantId, "TRON", "USDT"));
            assertFalse(chainRepository.withdrawalEnabled(tenantId, "TRON", "USDT"));
            var opened = service.setEnabled(principal, "TRON", true, "127.0.0.1");
            assertTrue(opened.enabled());
            service.requireActive(tenantId, "TRON");
            assertTrue(service.list(principal).stream()
                    .anyMatch(chain -> chain.chain().equals("TRON") && chain.enabled()));
            var enabledToken = service.list(principal).stream()
                    .filter(chain -> chain.chain().equals("TRON"))
                    .flatMap(chain -> chain.tokens().stream())
                    .filter(row -> row.symbol().equals("USDT"))
                    .findFirst().orElseThrow();
            assertTrue(enabledToken.platformEnabled());
            assertTrue(chainRepository.depositEnabled(tenantId, "TRON", "USDT"));
            assertTrue(chainRepository.withdrawalEnabled(tenantId, "TRON", "USDT"));
            Long tokenConfigId = jdbc.queryForObject("""
                    select id from token_config
                     where chain = 'TRON' and symbol = 'USDT' and enabled = true
                    """, Long.class);
            jdbc.update("update token_config set enabled = false where id = ?", tokenConfigId);
            var platformDisabledToken = service.list(principal).stream()
                    .filter(chain -> chain.chain().equals("TRON"))
                    .flatMap(chain -> chain.tokens().stream())
                    .filter(row -> row.symbol().equals("USDT"))
                    .findFirst().orElseThrow();
            assertFalse(platformDisabledToken.platformEnabled());
            assertFalse(chainRepository.depositEnabled(tenantId, "TRON", "USDT"));
            assertFalse(chainRepository.withdrawalEnabled(tenantId, "TRON", "USDT"));
            jdbc.update("update token_config set enabled = true where id = ?", tokenConfigId);
            assertTrue(chainRepository.depositEnabled(tenantId, "TRON", "USDT"));
            assertTrue(chainRepository.withdrawalEnabled(tenantId, "TRON", "USDT"));

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

    /**
     * 验证 {@code userAddressCreationIsIdempotentAndSharedAcrossEvmChains} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void userAddressCreationIsIdempotentAndSharedAcrossEvmChains() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository custody = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            CustodyTenantChainRepository tenantChainRepository =
                    new CustodyTenantChainRepository(jdbc);
            UUID tenantId = createTenant();
            UUID administratorId = UUID.randomUUID();
            String slug = jdbc.queryForObject(
                    "select slug from custody_tenant where id = ?", String.class, tenantId);
            jdbc.update("""
                    insert into custody_tenant_user(
                        id, tenant_id, email, display_name, password_hash, role, status)
                    values (?, ?, ?, 'Address administrator', 'test-only-hash',
                            'TENANT_ADMIN', 'ACTIVE')
                    """, administratorId, tenantId, slug + "@example.test");
            tenantChainRepository.setStatus(tenantId, "ETH", "ACTIVE", administratorId);
            tenantChainRepository.setStatus(tenantId, "BNB", "ACTIVE", administratorId);

            CustodyAddressService service = com.surprising.wallet.testutil.CustodyTestSupport.addresses(jdbc,
                    custody,
                    new StableEvmRuntime(jdbc),
                    new CustodyTenantChainService(
                            tenantChainRepository, custody,
                            new BlockchainRuntimeService(new com.surprising.wallet.repository.ChainProfileRepository(jdbc),
                            new com.surprising.wallet.repository.ChainAssetRepository(jdbc), null, null)));
            CustodyPrincipal principal = new CustodyPrincipal(
                    CustodyPrincipal.ActorType.TENANT_USER,
                    administratorId, tenantId, slug, "TENANT_ADMIN",
                    Set.of("addresses:read", "addresses:write"));

            var ethFirst = service.create(
                    principal,
                    new CustodyAddressService.CreateAddressCommand(
                            "ETH", "user_10086", null, null, null),
                    "API", "127.0.0.1");
            var ethRepeated = service.create(
                    principal,
                    new CustodyAddressService.CreateAddressCommand(
                            "ETH", "user_10086", 0L, null, null),
                    "API", "127.0.0.1");
            var bnbFirst = service.create(
                    principal,
                    new CustodyAddressService.CreateAddressCommand(
                            "BNB", "user_10086", 0L, null, null),
                    "API", "127.0.0.1");
            var ethRotated = service.create(
                    principal,
                    new CustodyAddressService.CreateAddressCommand(
                            "ETH", "user_10086", 1L, null, null),
                    "API", "127.0.0.1");
            var bnbRotated = service.create(
                    principal,
                    new CustodyAddressService.CreateAddressCommand(
                            "BNB", "user_10086", 1L, null, null),
                    "API", "127.0.0.1");

            assertEquals(ethFirst.id(), ethRepeated.id());
            assertEquals(0L, ethFirst.addressVersion());
            assertEquals(0L, bnbFirst.addressVersion());
            assertEquals(ethFirst.address(), bnbFirst.address());
            assertEquals(1L, ethRotated.addressVersion());
            assertEquals(1L, bnbRotated.addressVersion());
            assertEquals(ethRotated.address(), bnbRotated.address());
            assertNotEquals(ethFirst.address(), ethRotated.address());
            assertEquals(4, jdbc.queryForObject("""
                    select count(*) from custody_address
                     where tenant_id = ? and subject = 'user_10086'
                    """, Integer.class, tenantId));
            assertEquals(2, jdbc.queryForObject("""
                    select count(distinct address_version) from custody_address
                     where tenant_id = ? and subject = 'user_10086'
                    """, Integer.class, tenantId));
            assertEquals(1, jdbc.queryForObject("""
                    select count(distinct derivation_subject) from custody_address
                     where tenant_id = ? and subject = 'user_10086'
                    """, Integer.class, tenantId));
            var addressPage = service.page(principal, "", "", "", "", 50, 0);
            assertEquals(4L, addressPage.total());
            assertEquals(4, addressPage.items().size());
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from custody_audit_log where tenant_id = ? and action = 'ADDRESS.CREATE'
                       and resource_id = ? and details->>'addressVersion' = '1' and details->>'childIndex' = '1'
                    """, Integer.class, tenantId, ethRotated.id().toString()));
            status.setRollbackOnly();
        });
    }

    @Test
    void fundedWithdrawalAddressLookupChoosesOlderAddressWithAssetBalance() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository custody = com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc);
            CustodyTenantChainRepository tenantChains = new CustodyTenantChainRepository(jdbc);
            UUID tenantId = createTenant();
            UUID administratorId = UUID.randomUUID();
            String slug = jdbc.queryForObject(
                    "select slug from custody_tenant where id = ?", String.class, tenantId);
            jdbc.update("""
                    insert into custody_tenant_user(
                        id, tenant_id, email, display_name, password_hash, role, status)
                    values (?, ?, ?, 'Balance administrator', 'test-only-hash', 'TENANT_ADMIN', 'ACTIVE')
                    """, administratorId, tenantId, slug + "@example.test");
            tenantChains.setStatus(tenantId, "ETH", "ACTIVE", administratorId);
            CustodyAddressService service = com.surprising.wallet.testutil.CustodyTestSupport.addresses(jdbc,
                    custody, new StableEvmRuntime(jdbc),
                    new CustodyTenantChainService(tenantChains, custody,
                            new BlockchainRuntimeService(new com.surprising.wallet.repository.ChainProfileRepository(jdbc),
                            new com.surprising.wallet.repository.ChainAssetRepository(jdbc), null, null)));
            CustodyPrincipal principal = new CustodyPrincipal(
                    CustodyPrincipal.ActorType.TENANT_USER, administratorId, tenantId, slug,
                    "TENANT_ADMIN", Set.of("addresses:read", "addresses:write"));
            var v1 = service.create(principal,
                    new CustodyAddressService.CreateAddressCommand("ETH", "user_10086", 1L, null, null),
                    "API", "127.0.0.1");
            var v2 = service.create(principal,
                    new CustodyAddressService.CreateAddressCommand("ETH", "user_10086", 2L, null, null),
                    "API", "127.0.0.1");
            String v1Account = jdbc.queryForObject("""
                    select cha.account_id from chain_address cha
                    join custody_address ca on ca.chain_address_id = cha.id
                    where ca.tenant_id = ? and ca.id = ?
                    """, String.class, tenantId, v1.id());
            new LedgerBalanceRepository(jdbc).increment(
                    tenantId, "ETH", "ETH", v1Account, new BigDecimal("0.005"));

            List<Map<String, Object>> funded = service.fundedAddresses(
                    principal, "ETH", "ETH", "user_10086", "0.0005");

            assertEquals(1, funded.size());
            assertEquals(v1.id(), funded.getFirst().get("custodyAddressId"));
            assertNotEquals(v2.id(), funded.getFirst().get("custodyAddressId"));
            CustodyPrincipal noAddressRead = new CustodyPrincipal(
                    CustodyPrincipal.ActorType.TENANT_USER, administratorId, tenantId, slug,
                    "TENANT_ADMIN", Set.of("addresses:write"));
            assertThrows(CustodyForbiddenException.class, () -> service.fundedAddresses(
                    noAddressRead, "ETH", "ETH", "user_10086", "0.0005"));
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code dashboardListsOpenedNativeAssetsBeforeTheyHaveAddressesOrBalances} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void dashboardListsOpenedNativeAssetsBeforeTheyHaveAddressesOrBalances() {
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
                    new CustodyAssetDashboardRepository(jdbc), com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc));
            CustodyPrincipal principal = new CustodyPrincipal(
                    CustodyPrincipal.ActorType.TENANT_USER,
                    administratorId, tenantId, slug, "TENANT_ADMIN", java.util.Set.of("assets:read"));

            var dashboard = service.dashboard(principal);
            assertEquals(2, dashboard.assets().size());
            Map<String, CustodyAssetDashboardService.AssetRow> assets = dashboard.assets().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            CustodyAssetDashboardService.AssetRow::assetSymbol, asset -> asset));
            var tron = assets.get("TRX");
            assertEquals("TRON", tron.chain());
            assertEquals("TRX", tron.assetSymbol());
            assertTrue(tron.nativeAsset());
            assertEquals(0, BigDecimal.ZERO.compareTo(tron.totalBalance()));
            var usdt = assets.get("USDT");
            assertEquals("TRON", usdt.chain());
            assertFalse(usdt.nativeAsset());
            assertEquals(0, BigDecimal.ZERO.compareTo(usdt.totalBalance()));
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code assetDashboardAggregatesStablecoinsAcrossChainsWithoutFloatingPointMath} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void assetDashboardAggregatesStablecoinsAcrossChainsWithoutFloatingPointMath() {
        transactions.executeWithoutResult(status -> {
            UUID tenantId = createTenant();
            UUID administratorId = UUID.randomUUID();
            String slug = jdbc.queryForObject(
                    "select slug from custody_tenant where id = ?", String.class, tenantId);
            jdbc.update("""
                    insert into custody_tenant_user(
                        id, tenant_id, email, display_name, password_hash, role, status)
                    values (?, ?, ?, 'Dashboard administrator', 'test-only-hash',
                            'TENANT_ADMIN', 'ACTIVE')
                    """, administratorId, tenantId, slug + "@example.test");
            CustodyTenantChainRepository chainRepository =
                    new CustodyTenantChainRepository(jdbc);
            int namespace = jdbc.queryForObject(
                    "select derivation_namespace from custody_tenant where id = ?",
                    Integer.class, tenantId);
            String[] chains = {"TRON", "ARBITRUM"};
            BigDecimal[] totals = {new BigDecimal("5.25"), new BigDecimal("6.75")};
            for (int index = 0; index < chains.length; index++) {
                chainRepository.setStatus(
                        tenantId, chains[index], "ACTIVE", administratorId);
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
            jdbc.update("""
                    update token_config set enabled = false
                     where chain = 'TRON' and symbol = 'USDT' and enabled = true
                    """);

            CustodyAssetDashboardService service = new CustodyAssetDashboardService(
                    new CustodyAssetDashboardRepository(jdbc), com.surprising.wallet.testutil.CustodyTestSupport.repository(jdbc));
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

    /**
     * 验证 {@code createTenant} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code insertTestAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private TestAddress insertTestAddress(UUID tenantId, String chain, String assetSymbol,
                                          String subjectLabel, String address) {
        int namespace = jdbc.queryForObject(
                "select derivation_namespace from custody_tenant where id = ?",
                Integer.class, tenantId);
        int subject = jdbc.queryForObject(
                "select nextval('custody_derivation_subject_index_seq')::integer",
                Integer.class);
        Long chainAddressId = jdbc.queryForObject("""
                insert into chain_address(
                    tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                    address, derivation_path, wallet_role, enabled)
                values (?, ?, ?, ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                returning id
                """, Long.class, tenantId, chain, assetSymbol, address,
                Integer.toUnsignedLong(subject), namespace, address,
                "m/44'/60'/" + namespace + "'/" + subject + "/0");
        UUID custodyAddressId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, label, source, derivation_subject, derivation_child)
                values (?, ?, ?, ?, 'qa', ?, ?, ?, 'API', ?, 0)
                """, custodyAddressId, tenantId, chainAddressId, chain, address,
                subjectLabel, "Label " + subjectLabel, subject);
        return new TestAddress(custodyAddressId, address, Integer.toUnsignedLong(subject));
    }

    /**
     * 验证 {@code insertTestDeposit} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private void insertTestDeposit(UUID tenantId, TestAddress address, String assetSymbol,
                                   String txHash, String status) {
        Long depositRecordId = jdbc.queryForObject("""
                insert into deposit_record(
                    tenant_id, chain, asset_symbol, tx_hash, to_address,
                    amount, block_height, block_hash, confirmations, status,
                    credited, credit_generation, account_id, credited_at)
                values (?, 'ETH', ?, ?, ?, 12.5, 100, ?, 12, ?, true, 1, ?, now())
                returning id
                """, Long.class, tenantId, assetSymbol, txHash, address.address(), txHash,
                status, address.address());
        jdbc.update("""
                insert into custody_deposit(
                    id, tenant_id, custody_address_id, deposit_record_id,
                    chain, asset_symbol, tx_hash, amount, status, credited_at)
                values (?, ?, ?, ?, 'ETH', ?, ?, 12.5, ?, now())
                """, UUID.randomUUID(), tenantId, address.id(), depositRecordId,
                assetSymbol, txHash, status);
    }

    /**
     * 验证 {@code insertTestWithdrawal} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private void insertTestWithdrawal(CustodyRepository repository, UUID tenantId,
                                      TestAddress address, String orderNo,
                                      String externalReference, String destination,
                                      String txHash, String status) {
        jdbc.update("""
                insert into withdrawal_order(
                    tenant_id, order_no, user_id, chain, asset_symbol,
                    from_address, debit_account_id, to_address, amount, fee, status, tx_hash)
                values (?, ?, ?, 'ETH', 'USDT', ?, ?, ?, 3.5, 0.1, ?, ?)
                """, tenantId, orderNo, address.userId(), address.address(),
                address.address(), destination, status, txHash);
        repository.insertCustodyWithdrawal(
                UUID.randomUUID(), tenantId, address.id(), orderNo, externalReference, null,
                "ETH", "USDT", destination, new BigDecimal("3.5"),
                new BigDecimal("0.1"), status, "CONSOLE", "qa");
    }

    private static CustodyPrincipal historyPrincipal(UUID tenantId) {
        return new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER, UUID.randomUUID(), tenantId,
                "history-test", "VIEWER", Set.of("deposits:read", "withdrawals:read"));
    }

    private record TestAddress(UUID id, String address, long userId) {
    }

    /**
     * 验证 {@code insertWebhookEndpoint} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private UUID insertWebhookEndpoint(UUID tenantId, String name) {
        UUID endpointId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_webhook_endpoint(
                    id, tenant_id, name, url, secret_ciphertext, status, verified_at)
                values (?, ?, ?, ?, 'ciphertext', 'ACTIVE', now())
                """, endpointId, tenantId, name,
                "https://example.com/hooks/" + endpointId);
        return endpointId;
    }

    /**
     * 验证 {@code insertWebhookDelivery} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code quiesceExistingWebhookQueue} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 测试替身 {@code StableEvmRuntime}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class StableEvmRuntime extends BlockchainRuntimeService {
        /**
         * 保存 {@code jdbc}，用于访问当前测试所依赖的仓储、客户端或服务。
         */
        private final JdbcTemplate jdbc;

        /**
         * 验证 {@code StableEvmRuntime} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private StableEvmRuntime(JdbcTemplate jdbc) {
            super(null, null, null, null);
            this.jdbc = jdbc;
        }

        /**
         * 验证 {@code requireRuntime} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public RuntimeChain requireRuntime(String chain) {
            ChainType chainType = ChainType.valueOf(chain);
            String nativeSymbol = chainType == ChainType.BNB ? "BNB" : "ETH";
            return new RuntimeChain(
                    chainType, chain, "qa", "evm", nativeSymbol, 1);
        }

        /**
         * 验证 {@code generateDepositAddressAtIndex} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Address generateDepositAddressAtIndex(
                String chain, long userId, int biz, long childIndex) {
            RuntimeChain runtime = requireRuntime(chain);
            String address = "0x" + String.format("%032x%08x", userId, childIndex);
            String path = "m/44/60/" + biz + "/" + userId + "/" + childIndex;
            jdbc.update("""
                    insert into chain_address(
                        chain, asset_symbol, account_id, user_id, biz, address_index,
                        address, owner_address, derivation_path, wallet_role, enabled)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DEPOSIT', true)
                    """, chain, runtime.nativeSymbol(), address, userId, biz, childIndex,
                    address, address, path);
            return Address.builder()
                    .address(address)
                    .network(runtime.network())
                    .scriptType(runtime.family())
                    .derivationPath(path)
                    .biz(biz)
                    .currency(runtime.nativeSymbol().toLowerCase())
                    .userId(userId)
                    .index(Math.toIntExact(childIndex))
                    .build();
        }
    }
}
