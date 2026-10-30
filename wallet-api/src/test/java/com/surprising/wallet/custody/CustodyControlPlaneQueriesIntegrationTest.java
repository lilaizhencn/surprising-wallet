package com.surprising.wallet.custody;

import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.CustodyTenantUserRepository;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.service.CustodyTenantQueryService;
import com.surprising.wallet.service.CustodyTenantService;
import com.surprising.wallet.service.CustodyGasService;
import com.surprising.wallet.service.CustodyAssetDashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Query cost stays bounded while chain and account ownership remain distinct.
 */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyControlPlaneQueriesIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private CountingDataSource dataSource;

    private JdbcTemplate jdbc;

    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;

    private CustodyTenantQueryService queries;

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions {
    }

    @BeforeEach
    void setup() throws Exception {
        var source = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(source);
        dataSource = new CountingDataSource(source);
        jdbc = new JdbcTemplate(dataSource);
        context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        context.registerBean(CustodyTenantQueryService.class, () -> CustodyTestSupport.tenantQueries(jdbc));
        context.registerBean(CustodyTenantService.class, () -> CustodyTestSupport.tenants(jdbc));
        context.registerBean(CustodyGasService.class, () -> CustodyTestSupport.gas(jdbc));
        context.registerBean(CustodyAssetDashboardService.class, () -> CustodyTestSupport.assets(jdbc));
        context.registerBean(com.surprising.wallet.service.CustodyTenantChainService.class,
                () -> CustodyTestSupport.tenantChains(jdbc,
                        org.mockito.Mockito.mock(com.surprising.wallet.chain.BlockchainRuntimeService.class)));
        com.surprising.wallet.service.WalletConfigTestSupport.register(context, jdbc,
                new com.surprising.wallet.service.WalletRuntimeConfigService(
                        new com.surprising.wallet.repository.ChainProfileRepository(jdbc),
                        new com.surprising.wallet.repository.WalletSystemConfigRepository(jdbc)));
        context.refresh();
        queries = context.getBean(CustodyTenantQueryService.class);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void tenantPageQueryCostDoesNotGrowWithEveryReturnedTenant() {
        var tenants = new CustodyTenantTableRepository(jdbc);
        for (int i = 0; i < 20; i++) tenants.insert(UUID.randomUUID(), "query-cost-" + i, "Query tenant " + i);
        dataSource.selects.set(0);
        var result = queries.list(CustodyTestSupport.platformAdmin(), "query-cost-", "ACTIVE", 50, 0);
        var page = result.items();
        assertEquals(20, page.size());
        assertEquals(20, result.total());
        for (var row : page) {
            assertEquals(0L, row.addressCount());
            assertEquals(0L, row.gasAccountCount());
            assertEquals(0L, row.userCount());
        }
        assertTrue(dataSource.selects.get() <= 6, "tenant page must batch its counts, actual SELECTs=" + dataSource.selects.get());
        assertTrue(dataSource.snapshots.get() > 0, "tenant page must use a repeatable-read transaction");
    }

    @Test
    void gasListBatchesItsOwnedAddressAndNativeBalanceReads() {
        Map<String, BigDecimal> expected = new LinkedHashMap<>();
        int index = 0;
        for (String chain : List.of("ETH", "BASE", "BNB", "HYPEREVM", "POLYGON", "ARBITRUM")) {
            BigDecimal amount = BigDecimal.valueOf(++index);
            address(chain, "shared-gas-account", amount, true);
            expected.put(chain, amount);
        }
        dataSource.selects.set(0);
        var accounts = context.getBean(CustodyGasService.class).listForTenant(TENANT);
        assertEquals(6, accounts.size());
        for (var account : accounts) {
            assertEquals(0, expected.get(account.chain()).compareTo(account.availableBalance()));
            assertEquals(0, account.lockedBalance().signum());
        }
        assertTrue(dataSource.selects.get() <= 6, "gas list must batch its owned joins, actual SELECTs=" + dataSource.selects.get());
        assertTrue(dataSource.snapshots.get() > 0, "gas list must use a repeatable-read transaction");
    }

    @Test
    void gasOnOneChainCannotHideAnotherChainsCustomerBalanceAfterThatChainCloses() {
        address("ETH", "shared-account", new BigDecimal("0.9"), true);
        address("BASE", "shared-account", new BigDecimal("7"), false);
        jdbc.update("update chain_profile set enabled=false where chain='BASE'");
        var rows = context.getBean(CustodyAssetDashboardService.class).observedBalancesForTenant(TENANT);
        assertEquals(1, rows.size());
        assertEquals("BASE", rows.getFirst().chain());
        assertEquals(0, new BigDecimal("7").compareTo(rows.getFirst().totalBalance()));
        assertEquals(1L, rows.getFirst().addressCount());
    }

    @Test
    void dashboardCannotMergeCaseDistinctLedgerAccountsWithTheGasAccount() {
        address("ETH", "GasAccount", new BigDecimal("0.9"), true);
        address("ETH", "gasaccount", new BigDecimal("7"), false);
        UUID administrator = UUID.randomUUID();
        new CustodyTenantUserRepository(jdbc).insertTenantAdmin(administrator, TENANT, "query-admin@example.test", "Query admin", "fixture-password-hash");
        jdbc.update("""
            insert into custody_tenant_chain(tenant_id, chain, status, opened_by, opened_at)
            values (?, 'ETH', 'ACTIVE', ?, now())
            on conflict (tenant_id, chain) do update set status='ACTIVE',
                opened_by=excluded.opened_by, opened_at=now(), closed_at=null
            """, TENANT, administrator);
        String network = jdbc.queryForObject("select network from custody_gas_account where tenant_id=? and chain='ETH'", String.class, TENANT);
        jdbc.update("update chain_profile set enabled=true where chain='ETH' and network=?", network);
        var row = context.getBean(CustodyAssetDashboardService.class).dashboard(CustodyTestSupport.tenantAdmin(TENANT)).assets().stream().filter(value -> "ETH".equals(value.chain()) && "ETH".equals(value.assetSymbol())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("7").compareTo(row.totalBalance()));
        assertEquals(1, row.addressCount());
        String pool=com.surprising.wallet.service.CustodyPoolFundingService.accountId(TENANT,"ETH","ETH");
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH',?,1,1,2)",TENANT,pool);
        var pooled=context.getBean(CustodyAssetDashboardService.class).dashboard(CustodyTestSupport.tenantAdmin(TENANT)).assets().stream().filter(value -> "ETH".equals(value.chain()) && "ETH".equals(value.assetSymbol())).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("9").compareTo(pooled.totalBalance()));
        assertEquals(1,pooled.addressCount());
    }

    @Test
    void tenantSearchIsLiteralAndPageJsonRetainsItsPublicFieldNames() throws Exception {
        UUID literal = UUID.randomUUID();
        var tenants = new CustodyTenantTableRepository(jdbc);
        tenants.insert(literal, "literal-one", "Literal_% tenant");
        tenants.insert(UUID.randomUUID(), "literal-two", "LiteralAA tenant");
        var page = queries.list(CustodyTestSupport.platformAdmin(), "Literal_%", "ACTIVE", 20, 0);
        assertEquals(1, page.total());
        assertEquals(literal, page.items().getFirst().id());
        var mapper = new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper();
        var item = mapper.readTree(mapper.writeValueAsString(page)).path("items").get(0);
        for (String field : List.of("derivation_namespace", "ip_allowlist_enabled", "display_currency", "created_at", "updated_at", "addressCount", "gasAccountCount", "userCount")) assertTrue(item.has(field), field);
        assertFalse(item.has("derivationNamespace"));
    }

    @Test
    void duplicateAdministratorRollsBackTenantCreationAndItsAudit() {
        var service = context.getBean(CustodyTenantService.class);
        var actor = CustodyTestSupport.platformAdmin();
        service.create(actor, new CustodyTenantService.CreateTenantCommand("owner-first", "First tenant", "owner@example.test", "Owner", "StrongPassword123!"), "127.0.0.1");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> service.create(actor, new CustodyTenantService.CreateTenantCommand("owner-second", "Second tenant", "owner@example.test", "Owner", "StrongPassword123!"), "127.0.0.1"));
        assertEquals(0, jdbc.queryForObject("select count(*) from custody_tenant where slug='owner-second'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_tenant_user where email='owner@example.test'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_audit_log where action='TENANT.CREATE' and actor_id=?", Integer.class, actor.actorId().toString()));
    }

    @Test
    void unfinishedIdempotencyReceiptIsReportedAsProcessingWithoutCreatingAnotherWithdrawal() {
        UUID addressId = address("ETH", "waiting-customer", new BigDecimal("7"), false);
        var properties = new com.surprising.wallet.model.CustodySecurityProperties();
        properties.setSecretMasterKey("22".repeat(32));
        var crypto = new com.surprising.wallet.service.CustodyCryptoService(properties);
        var execution = org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyWithdrawalExecutionService.class);
        var idempotency = new com.surprising.wallet.repository.CustodyIdempotencyRepository(jdbc);
        assertTrue(idempotency.begin(TENANT, "waiting-request", "WITHDRAWAL.CREATE", crypto.sha256("ADDRESS\n" + addressId + "\nuser:42\nETH\nETH\nrecipient\n1\nclient-ref"), null));
        var service = new com.surprising.wallet.service.CustodyWithdrawalService(execution, context.getBean(CustodyGasService.class), org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyTenantChainService.class), crypto, new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper(), CustodyTestSupport.events(jdbc), new com.surprising.wallet.repository.CustodyAddressRepository(jdbc), new com.surprising.wallet.repository.CustodyGasAccountRepository(jdbc), new CustodyTenantTableRepository(jdbc), new com.surprising.wallet.repository.ChainAddressRepository(jdbc), new com.surprising.wallet.repository.CustodyReorgDeficitRepository(jdbc), idempotency, new com.surprising.wallet.repository.CustodyWithdrawalRepository(jdbc), new com.surprising.wallet.repository.CustodyAuditRepository(jdbc), CustodyTestSupport.poolFunding(jdbc));
        var failure = assertThrows(IllegalStateException.class, () -> service.create(CustodyTestSupport.tenantAdmin(TENANT), new com.surprising.wallet.service.CustodyWithdrawalService.CreateWithdrawalCommand(addressId, "ETH", "ETH", "recipient", "1", "client-ref", true, "user:42", com.surprising.wallet.service.CustodyWithdrawalService.FundingSource.ADDRESS), "API", "waiting-request", "127.0.0.1"));
        assertTrue(failure.getMessage().contains("still processing"), failure.getMessage());
        org.mockito.Mockito.verifyNoInteractions(execution);
        assertEquals(0, jdbc.queryForObject("select count(*) from custody_withdrawal where tenant_id=?", Integer.class, TENANT));
    }

    @Test
    void tenantChainListUsesBoundedTypedReadsAndAnActualConsistentSnapshot() {
        dataSource.selects.set(0);
        var rows = context.getBean(com.surprising.wallet.service.CustodyTenantChainService.class)
                .list(CustodyTestSupport.tenantAdmin(TENANT));
        int selects = dataSource.selects.get();
        assertTrue(rows.size() > 10, "fixture must exercise many enabled chains");
        assertTrue(selects <= 7, "tenant chain reads must not grow per chain; SELECTs=" + selects);
        assertTrue(dataSource.snapshots.get() > 0, "chain list must use its normal Spring transaction");
        assertTrue(rows.stream().anyMatch(row -> "BTC".equals(row.chain())), "typed profiles must retain UTXO chains");
        for (var row : rows) {
            assertTrue(row.assetSymbols().contains(row.nativeSymbol()));
            var network = new com.surprising.wallet.repository.TokenConfigRepository(jdbc)
                    .listByChainAndNetwork(row.chain(), row.network());
            for (var token : row.tokens()) assertTrue(network.stream().anyMatch(config ->
                    config.symbol().equals(token.symbol()) && java.util.Objects.equals(config.contractAddress(), token.contractAddress())
                            && config.enabled() == token.platformEnabled()), row.chain() + "/" + row.network());
        }
    }

    @Test
    void faucetBatchesDiscoveryAndKeepsConsoleGasAndDuplicateFundingRules() {
        for (int i = 0; i < 20; i++) address("ETH", "faucet-customer-" + i, BigDecimal.ZERO, false);
        UUID gas = address("ETH", "faucet-gas", BigDecimal.ZERO, true);
        UUID console = address("ETH", "faucet-console", BigDecimal.ZERO, false);
        jdbc.update("update custody_address set source='CONSOLE' where id in (?, ?)", gas, console);
        jdbc.update("update chain_profile set scan_enabled=true where chain='ETH' and enabled=true");
        jdbc.update("update token_config set enabled=false where chain='ETH'");
        var actor = UUID.randomUUID();
        new CustodyTenantUserRepository(jdbc).insertTenantAdmin(actor, TENANT,
                "faucet-admin@example.test", "Faucet admin", "fixture-password-hash");
        new com.surprising.wallet.repository.CustodyTenantChainTableRepository(jdbc)
                .setStatus(TENANT, "ETH", "ACTIVE", actor);
        var properties = new com.surprising.wallet.devfaucet.DevFaucetProperties();
        properties.setEnabled(true);
        properties.setBatchSize(100);
        properties.getEvm().setFromAddress("0x" + "ab".repeat(20));
        var sent = new java.util.ArrayList<com.surprising.wallet.devfaucet.DevFaucetFunding>();
        com.surprising.wallet.service.DevFaucetRpcClient rpc = funding -> {
            sent.add(funding);
            return "0x" + String.format("%064x", sent.size());
        };
        var faucet = new com.surprising.wallet.service.DevFaucetService(properties,
                new com.surprising.wallet.repository.DevFaucetFundingRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new CustodyTenantTableRepository(jdbc),
                new com.surprising.wallet.repository.CustodyTenantChainTableRepository(jdbc),
                new com.surprising.wallet.repository.CustodyGasAccountRepository(jdbc),
                new com.surprising.wallet.repository.ChainProfileRepository(jdbc),
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.TokenConfigRepository(jdbc),
                new com.surprising.wallet.repository.CustodyDepositRepository(jdbc), rpc,
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc), new tools.jackson.databind.ObjectMapper(),
                new com.surprising.wallet.devfaucet.DevFaucetAmountGenerator(), "test");
        faucet.validate();
        dataSource.selects.set(0);
        faucet.runOnce();
        int selects = dataSource.selects.get();
        assertEquals(21, sent.size());
        assertTrue(selects <= 10, "discovery must batch its chain and existence reads; SELECTs=" + selects);
        assertEquals(1, sent.stream().filter(row -> row.custodyAddressId().equals(gas)
                && "TENANT_GAS".equals(row.purpose()) && "ETH".equals(row.assetSymbol())).count());
        assertFalse(sent.stream().anyMatch(row -> row.custodyAddressId().equals(console)));
        faucet.runOnce();
        assertEquals(21, sent.size(), "existing SENT tasks must not be discovered or sent again");
        assertEquals(21, jdbc.queryForObject("select count(*) from custody_dev_faucet_funding where status='SENT'",
                Integer.class));
    }

    @Test
    void configurationChainListBatchesTokenAndRpcCountsAcrossDisabledNetworks() {
        var profiles = new com.surprising.wallet.repository.ChainProfileRepository(jdbc);
        var profile = profiles.findAccountByChain("ETH").orElseThrow();
        new com.surprising.wallet.repository.TokenConfigRepository(jdbc).insert("ETH", profile.getNetwork(),
                "COUNT_ONLY", "ERC20", "0x" + "12".repeat(20), null, null, 6, false,
                BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.ZERO, "NATIVE", 1);
        var runtime = new com.surprising.wallet.service.WalletRuntimeConfigService(profiles,
                new com.surprising.wallet.repository.WalletSystemConfigRepository(jdbc));
        var service = context.getBean(com.surprising.wallet.service.WalletChainConfigService.class);
        dataSource.selects.set(0);
        var rows = service.listChains(CustodyTestSupport.platformAdmin(), "", null, "", "", "",
                null, null, null, null);
        int queries = dataSource.selects.get();
        assertTrue(rows.size() > 20);
        assertTrue(rows.stream().anyMatch(row -> !row.enabled()));
        var row = rows.stream().filter(value -> "ETH".equals(value.chain())
                && profile.getNetwork().equals(value.network())).findFirst().orElseThrow();
        assertTrue(row.tokenSymbols().contains("COUNT_ONLY"), "disabled tokens still count as configured");
        assertEquals(jdbc.queryForObject("select count(*) from token_config where chain='ETH' and network=?",
                Integer.class, profile.getNetwork()), row.tokenCount());
        assertEquals(jdbc.queryForObject("select count(*) from chain_rpc_node where chain='ETH' and network=?",
                Integer.class, profile.getNetwork()), row.rpcCount());
        assertNotNull(row.createdAt());
        assertNotNull(row.updatedAt());
        assertTrue(queries <= 3, "chain summaries must read each table once; SELECTs=" + queries);
        assertTrue(dataSource.snapshots.get() > 0, "configuration list must use its real read transaction");
        dataSource.selects.set(0);
        var detail = service.getChain(CustodyTestSupport.platformAdmin(), row.id());
        int detailQueries = dataSource.selects.get();
        assertEquals(row, detail.chain());
        assertTrue(detailQueries <= 5, "detail must reuse its profile and configured rows; SELECTs=" + detailQueries);
    }

    @Test
    void configurationTokenListBatchesProfilesAndAssetsAndPreservesNetworkAndEffectiveFilters() {
        var profiles = new com.surprising.wallet.repository.ChainProfileRepository(jdbc);
        var profile = profiles.findAccountByChain("ETH").orElseThrow();
        var tokens = new com.surprising.wallet.repository.TokenConfigRepository(jdbc);
        var assets = new com.surprising.wallet.repository.ChainAssetRepository(jdbc);
        for (int i = 0; i < 20; i++) {
            String symbol = "BATCH_TOKEN_" + i;
            String contract = "0x" + String.format(Locale.ROOT, "%040x", 1000L + i);
            tokens.insert("ETH", profile.getNetwork(), symbol, "ERC20", contract, null, null,
                    6, i % 2 == 0, new BigDecimal("0.123456"), BigDecimal.ZERO, false,
                    BigDecimal.ZERO, "NATIVE", 1);
            assets.upsertNonNative("ETH", symbol, "ERC20", contract, 6, i % 3 != 0,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
        String closedNetwork = jdbc.queryForObject("select network from chain_profile where chain='ETH' and enabled=false limit 1",
                String.class);
        tokens.insert("ETH", closedNetwork, "BATCH_TOKEN_2", "ERC20", "0x" + "34".repeat(20), null, null,
                6, false, new BigDecimal("0.123456"), BigDecimal.ZERO, false, BigDecimal.ZERO, "NATIVE", 1);
        jdbc.update("update chain_profile set scan_enabled=true where chain='ETH' and enabled=true");
        var settings = new com.surprising.wallet.repository.WalletSystemConfigRepository(jdbc);
        settings.upsertBoolean("global.all.enabled", true);
        settings.upsertBoolean("global.scan.enabled", true);
        var service = context.getBean(com.surprising.wallet.service.WalletTokenConfigService.class);
        dataSource.selects.set(0);
        var rows = service.listTokens(CustodyTestSupport.platformAdmin(), "BATCH_TOKEN_", "ETH", "", "", null, null);
        int queries = dataSource.selects.get();
        assertEquals(21, rows.size());
        var closed = rows.stream().filter(row -> closedNetwork.equals(row.network())).findFirst().orElseThrow();
        assertFalse(closed.chainEnabled());
        assertFalse(closed.effectiveEnabled());
        assertTrue(closed.blockers().contains("Chain network is disabled."));
        var selected = service.listTokens(CustodyTestSupport.platformAdmin(), "BATCH_TOKEN_", "ETH",
                profile.getNetwork(), "ERC20", null, true);
        assertEquals(6, selected.size());
        assertTrue(selected.stream().allMatch(row -> row.enabled() && row.assetActive() && row.effectiveEnabled()));
        assertEquals(0, new BigDecimal("0.123456").compareTo(selected.getFirst().minDeposit()));
        assertTrue(queries <= 4, "token enrichment must batch its metadata; SELECTs=" + queries);
        assertTrue(dataSource.snapshots.get() > 0, "token views must use their real read transaction");
    }

    @Test
    void typedRpcUpdatesPreserveCredentialsAndCannotRemoveTheLastRequiredNode() throws Exception {
        var profiles = new com.surprising.wallet.repository.ChainProfileRepository(jdbc);
        var profile = profiles.findAccountByChain("ETH").orElseThrow();
        jdbc.update("update chain_rpc_node set enabled=false where chain='ETH' and network=? and environment='test' and purpose='rpc'",
                profile.getNetwork());
        var service = context.getBean(com.surprising.wallet.service.WalletRpcConfigService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        var actor = CustodyTestSupport.platformAdmin();
        var created = service.createRpcNode(actor, profile.getId(),
                new com.surprising.wallet.service.WalletRpcConfigService.RpcNodeCommand(
                        "test", "typed-rpc-write", "rpc", "HTTP_JSON_RPC", "http://127.0.0.1:1",
                        "BEARER", null, "synthetic-only-key", null, null, 100, 0, true, null, null), "127.0.0.1");
        var updated = service.updateRpcNode(actor, profile.getId(), created.id(),
                new com.surprising.wallet.service.WalletRpcConfigService.RpcNodeCommand(
                        null, null, null, null, null, null, null, null, null, null, 17, null, null, null, null), "127.0.0.1");
        assertEquals(17, updated.priority());
        assertTrue(updated.apiKeyConfigured());
        var stored = new com.surprising.wallet.repository.ChainRpcNodeRepository(jdbc)
                .findById(created.id(), "ETH", profile.getNetwork()).orElseThrow();
        assertEquals("synthetic-only-key", stored.getApiKey());
        var mapper = new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(updated));
        assertTrue(json.path("apiKeyConfigured").asBoolean());
        assertFalse(json.has("apiKey"));
        assertFalse(mapper.writeValueAsString(updated).contains("synthetic-only-key"));
        assertThrows(IllegalStateException.class, () -> service.deleteRpcNode(actor, profile.getId(), created.id(), "127.0.0.1"));
        var refusal = assertThrows(IllegalStateException.class, () -> service.updateRpcNode(actor, profile.getId(), created.id(),
                new com.surprising.wallet.service.WalletRpcConfigService.RpcNodeCommand(
                        null, null, null, null, null, null, null, null, null, null, null, null, false, null, null), "127.0.0.1"));
        assertTrue(refusal.getMessage().contains("retain an enabled RPC node"));
        assertTrue(new com.surprising.wallet.repository.ChainRpcNodeRepository(jdbc)
                .findById(created.id(), "ETH", profile.getNetwork()).orElseThrow().getEnabled());
    }

    @Test
    void rpcCreationRollsBackItsRowWhenConfigurationAuditFails() {
        var profile = new com.surprising.wallet.repository.ChainProfileRepository(jdbc).findAccountByChain("ETH").orElseThrow();
        var service = context.getBean(com.surprising.wallet.service.WalletRpcConfigService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        jdbc.execute("""
                create function reject_config_audit_test() returns trigger language plpgsql as $$
                begin
                    if NEW.action='WALLET_RPC.CREATE' then raise exception 'configuration audit rejected by test'; end if;
                    return NEW;
                end $$
                """);
        jdbc.execute("create trigger reject_config_audit_test before insert on custody_audit_log for each row execute function reject_config_audit_test()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> service.createRpcNode(CustodyTestSupport.platformAdmin(), profile.getId(),
                            new com.surprising.wallet.service.WalletRpcConfigService.RpcNodeCommand(
                                    "test", "typed-rpc-reject", "rpc", "HTTP_JSON_RPC", "http://127.0.0.1:1",
                                    "NONE", null, null, null, null, 100, 0, false, null, null), "127.0.0.1"));
            assertEquals(0, jdbc.queryForObject("select count(*) from chain_rpc_node where node_label='typed-rpc-reject'", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from custody_audit_log where action='WALLET_RPC.CREATE'", Integer.class));
        } finally {
            jdbc.execute("drop trigger reject_config_audit_test on custody_audit_log");
            jdbc.execute("drop function reject_config_audit_test()");
        }
    }

    @Test
    void tokenOwnerValidatesItsContractAndCommitsAssetAndAuditWithStatusChanges() throws Exception {
        var profile = new com.surprising.wallet.repository.ChainProfileRepository(jdbc).findAccountByChain("ETH").orElseThrow();
        String symbol = "OWNER_TOKEN";
        String encodedSymbol = "0x" + java.util.HexFormat.of().formatHex(
                java.util.Arrays.copyOf(symbol.getBytes(java.nio.charset.StandardCharsets.UTF_8), 32));
        var calls = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = new tools.jackson.databind.ObjectMapper();
        server.createContext("/", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String result = switch (request.path("method").asText()) {
                case "eth_chainId" -> "0x" + Long.toHexString(profile.getChainId());
                case "eth_getCode" -> "0x6001";
                case "eth_call" -> "0x313ce567".equals(request.path("params").get(0).path("data").asText())
                        ? "0x6" : encodedSymbol;
                default -> throw new AssertionError("unexpected method " + request.path("method"));
            };
            calls.incrementAndGet();
            byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + result + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            jdbc.update("update chain_rpc_node set enabled=false where chain='ETH' and network=? and environment='test'", profile.getNetwork());
            new com.surprising.wallet.repository.ChainRpcNodeRepository(jdbc).insert("ETH", profile.getNetwork(),
                    "test", "owner-contract", "rpc", "HTTP_JSON_RPC",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "NONE", null, null, null, null,
                    0, 0, true, null, null);
            var service = context.getBean(com.surprising.wallet.service.WalletTokenConfigService.class);
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
            var created = service.createToken(CustodyTestSupport.platformAdmin(),
                    new com.surprising.wallet.service.WalletTokenConfigService.TokenCommand("ETH", profile.getNetwork(),
                            symbol, "ERC20", "0x" + "56".repeat(20), null, null, 6, true, true,
                            new BigDecimal("0.123456"), new BigDecimal("0.234567"), BigDecimal.ONE, "NATIVE", 2), "127.0.0.1");
            assertTrue(created.enabled());
            assertTrue(created.assetActive());
            assertTrue(created.collectEnabled());
            assertEquals(4, calls.get());
            var assets = new com.surprising.wallet.repository.ChainAssetRepository(jdbc);
            assertEquals(0, new BigDecimal("0.234567").compareTo(assets.find("ETH", symbol).orElseThrow().getMinTransfer()));
            assertEquals(0, new BigDecimal("0.234567").compareTo(assets.find("ETH", symbol).orElseThrow().getMinWithdraw()));
            jdbc.execute("""
                    create function reject_token_asset_audit_test() returns trigger language plpgsql as $$
                    begin
                        if NEW.action='WALLET_TOKEN.UPDATE' then raise exception 'token asset audit rejected by test'; end if;
                        return NEW;
                    end $$
                    """);
            jdbc.execute("create trigger reject_token_asset_audit_test before insert on custody_audit_log for each row execute function reject_token_asset_audit_test()");
            try {
                assertThrows(org.springframework.dao.DataAccessException.class, () -> service.updateToken(
                        CustodyTestSupport.platformAdmin(), created.id(), tokenPatch(null, new BigDecimal("1.234567")), "127.0.0.1"));
                assertEquals(0, created.minWithdraw().compareTo(new com.surprising.wallet.repository.TokenConfigRepository(jdbc)
                        .findById(created.id()).orElseThrow().minWithdraw()));
                assertEquals(0, created.minWithdraw().compareTo(assets.find("ETH", symbol).orElseThrow().getMinTransfer()));
                assertEquals(0, created.minWithdraw().compareTo(assets.find("ETH", symbol).orElseThrow().getMinWithdraw()));
            } finally {
                jdbc.execute("drop trigger reject_token_asset_audit_test on custody_audit_log");
                jdbc.execute("drop function reject_token_asset_audit_test()");
            }
            var disabled = service.updateTokenStatus(CustodyTestSupport.platformAdmin(), created.id(),
                    new com.surprising.wallet.service.WalletTokenConfigService.TokenStatusCommand(false), "127.0.0.1");
            assertFalse(disabled.enabled());
            assertFalse(disabled.assetActive());
            assertFalse(disabled.collectEnabled());
            assertFalse(assets.find("ETH", symbol).orElseThrow().getActive());
            var enabled = service.updateTokenStatus(CustodyTestSupport.platformAdmin(), created.id(),
                    new com.surprising.wallet.service.WalletTokenConfigService.TokenStatusCommand(true), "127.0.0.1");
            assertTrue(enabled.enabled());
            assertTrue(enabled.assetActive());
            assertFalse(enabled.collectEnabled(), "status changes must not restore a previous collection switch");
            assertEquals(12, calls.get());
            assertEquals(3, jdbc.queryForObject("select count(*) from custody_audit_log where resource_type='TOKEN_CONFIG' and resource_id=?",
                    Integer.class, String.valueOf(created.id())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chainAndTokenOwnersRetainFinancialIdentityGuardsAndAllowUnrelatedSettings() {
        var profile = new com.surprising.wallet.repository.ChainProfileRepository(jdbc).findAccountByChain("ETH").orElseThrow();
        jdbc.update("update chain_profile set enabled=false where id=?", profile.getId());
        var tokens = context.getBean(com.surprising.wallet.service.WalletTokenConfigService.class);
        var actor = CustodyTestSupport.platformAdmin();
        var token = tokens.createToken(actor, new com.surprising.wallet.service.WalletTokenConfigService.TokenCommand(
                "ETH", profile.getNetwork(), "OWNER_LOCK", "ERC20", "0x" + "78".repeat(20), null, null,
                6, false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "NATIVE", 1), "127.0.0.1");
        jdbc.update("""
                insert into ledger_balance(tenant_id, chain, asset_symbol, account_id,
                    available_balance, locked_balance, total_balance) values (?, 'ETH', 'OWNER_LOCK', 'owner-lock', 1, 0, 1)
                """, TENANT);
        var chain = context.getBean(com.surprising.wallet.service.WalletChainConfigService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(chain));
        var chainRefusal = assertThrows(IllegalStateException.class, () -> chain.updateChain(actor, profile.getId(),
                chainPatch(profile.getRuntimeCurrencyId() + 1000, null), "127.0.0.1"));
        assertTrue(chainRefusal.getMessage().contains("locked"));
        var tokenRefusal = assertThrows(IllegalStateException.class, () -> tokens.updateToken(actor, token.id(),
                tokenPatch(5, null), "127.0.0.1"));
        assertTrue(tokenRefusal.getMessage().contains("locked"));
        assertEquals(profile.getRuntimeCurrencyId(), jdbc.queryForObject("select runtime_currency_id from chain_profile where id=?", Integer.class, profile.getId()));
        assertEquals(6, jdbc.queryForObject("select decimals from token_config where id=?", Integer.class, token.id()));
        var changed = tokens.updateToken(actor, token.id(), tokenPatch(null, new BigDecimal("0.123456")), "127.0.0.1");
        assertEquals(0, new BigDecimal("0.123456").compareTo(changed.minWithdraw()));
        assertEquals(101, chain.updateChain(actor, profile.getId(), chainPatch(null, 101), "127.0.0.1").chain().scanBatchSize());
    }

    @Test
    void chainAndTokenWritesRollBackWhenTheirConfigurationAuditFails() {
        var profile = new com.surprising.wallet.repository.ChainProfileRepository(jdbc).findAccountByChain("ETH").orElseThrow();
        jdbc.update("update chain_profile set enabled=false where id=?", profile.getId());
        var actor = CustodyTestSupport.platformAdmin();
        var tokens = context.getBean(com.surprising.wallet.service.WalletTokenConfigService.class);
        var token = tokens.createToken(actor, new com.surprising.wallet.service.WalletTokenConfigService.TokenCommand(
                "ETH", profile.getNetwork(), "OWNER_ROLLBACK", "ERC20", "0x" + "9a".repeat(20), null, null,
                6, false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "NATIVE", 1), "127.0.0.1");
        jdbc.execute("""
                create function reject_owner_audit_test() returns trigger language plpgsql as $$
                begin
                    if NEW.action in ('WALLET_CHAIN.UPDATE', 'WALLET_TOKEN.UPDATE') then
                        raise exception 'configuration owner audit rejected by test';
                    end if;
                    return NEW;
                end $$
                """);
        jdbc.execute("create trigger reject_owner_audit_test before insert on custody_audit_log for each row execute function reject_owner_audit_test()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () ->
                    context.getBean(com.surprising.wallet.service.WalletChainConfigService.class).updateChain(actor,
                            profile.getId(), chainPatch(null, 101), "127.0.0.1"));
            assertThrows(org.springframework.dao.DataAccessException.class, () ->
                    tokens.updateToken(actor, token.id(), tokenPatch(null, BigDecimal.ONE), "127.0.0.1"));
            assertEquals(profile.getScanBatchSize(), jdbc.queryForObject("select scan_batch_size from chain_profile where id=?", Integer.class, profile.getId()));
            assertEquals(0, jdbc.queryForObject("select min_withdraw from token_config where id=?", BigDecimal.class, token.id()).signum());
            assertEquals(0, jdbc.queryForObject("select count(*) from custody_audit_log where action in ('WALLET_CHAIN.UPDATE', 'WALLET_TOKEN.UPDATE')", Integer.class));
        } finally {
            jdbc.execute("drop trigger reject_owner_audit_test on custody_audit_log");
            jdbc.execute("drop function reject_owner_audit_test()");
        }
    }

    @Test
    void eachConfigurationOwnerRejectsTenantAndAnonymousWritesBeforeValidation() {
        var chains = context.getBean(com.surprising.wallet.service.WalletChainConfigService.class);
        var rpc = context.getBean(com.surprising.wallet.service.WalletRpcConfigService.class);
        var tokens = context.getBean(com.surprising.wallet.service.WalletTokenConfigService.class);
        for (var actor : new com.surprising.wallet.model.CustodyPrincipal[]{null, CustodyTestSupport.tenantAdmin(TENANT)}) {
            assertThrows(com.surprising.wallet.exception.CustodyForbiddenException.class, () -> chains.createChain(actor, null, "127.0.0.1"));
            assertThrows(com.surprising.wallet.exception.CustodyForbiddenException.class, () -> rpc.createRpcNode(actor, -1, null, "127.0.0.1"));
            assertThrows(com.surprising.wallet.exception.CustodyForbiddenException.class, () -> tokens.createToken(actor, null, "127.0.0.1"));
        }
    }

    private static com.surprising.wallet.service.WalletChainConfigService.ChainCommand chainPatch(Integer runtimeId, Integer scanBatch) {
        return new com.surprising.wallet.service.WalletChainConfigService.ChainCommand(null, null, null, runtimeId,
                null, null, null, null, null, null, null, null, null, null, null, scanBatch, null, null, null, null, null, null);
    }

    private static com.surprising.wallet.service.WalletTokenConfigService.TokenCommand tokenPatch(Integer decimals, BigDecimal minWithdraw) {
        return new com.surprising.wallet.service.WalletTokenConfigService.TokenCommand(null, null, null, null, null,
                null, null, decimals, null, null, null, minWithdraw, null, null, null);
    }

    private UUID address(String chain, String account, BigDecimal balance, boolean gas) {
        var profile = jdbc.queryForMap("""
            select network, native_symbol from chain_profile where chain=?
             order by enabled desc, network limit 1
            """, chain);
        String symbol = (String) profile.get("native_symbol");
        int namespace = jdbc.queryForObject("select derivation_namespace from custody_tenant where id=?", Integer.class, TENANT);
        int subject = jdbc.queryForObject("select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        String address = account + "-" + chain;
        long chainAddress = jdbc.queryForObject("""
            insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                address_index, address, derivation_path, wallet_role, enabled)
            values (?, ?, ?, ?, ?, ?, 0, ?, 'query-test', 'DEPOSIT', true) returning id
            """, Long.class, TENANT, chain, symbol, account, Integer.toUnsignedLong(subject), namespace, address);
        UUID custodyAddress = UUID.randomUUID();
        jdbc.update("""
            insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                subject, source, derivation_subject, derivation_child)
            values (?, ?, ?, ?, ?, ?, ?, 'API', ?, 0)
            """, custodyAddress, TENANT, chainAddress, chain, profile.get("network"), address, address, subject);
        if (gas) {
            jdbc.update("""
                insert into custody_gas_account(id, tenant_id, custody_address_id, chain, network,
                    native_symbol, low_balance_threshold) values (?, ?, ?, ?, ?, ?, 0.1)
                """, UUID.randomUUID(), TENANT, custodyAddress, chain, profile.get("network"), symbol);
        }
        jdbc.update("""
            insert into ledger_balance(tenant_id, chain, asset_symbol, account_id,
                available_balance, locked_balance, total_balance) values (?, ?, ?, ?, ?, 0, ?)
            """, TENANT, chain, symbol, account, balance, balance);
        return custodyAddress;
    }

    /**
     * Count driver executions rather than repository methods, so batching is observable.
     */
    private static final class CountingDataSource extends DelegatingDataSource {

        private final AtomicInteger selects = new AtomicInteger();

        private final AtomicInteger snapshots = new AtomicInteger();

        CountingDataSource(DataSource source) {
            super(source);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(super.getConnection());
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return connection(super.getConnection(user, password));
        }

        private Connection connection(Connection target) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                if (method.getName().equals("setTransactionIsolation") && args != null && Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(args[0])) {
                    snapshots.incrementAndGet();
                }
                Object result = invoke(target, method, args);
                if (result instanceof Statement statement) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                    Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, values) -> {
                        String executing = sql;
                        if (executing == null) {
                            if (values != null && values.length > 0 && values[0] instanceof String text) {
                                executing = text;
                            } else
                                executing = "";
                        }
                        if (m.getName().startsWith("execute") && executing.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")) {
                            selects.incrementAndGet();
                        }
                        return invoke(statement, m, values);
                    });
                }
                return result;
            });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }
}
