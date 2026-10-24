package com.surprising.wallet.custody;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.CustodyTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationIntegrationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() throws Exception {
        var source = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(source);
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test
    void runtimeProfileAndAssetQueriesUseTheSameCanonicalModels() {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var profiles = new ChainProfileRepository(jdbc);
            assertFalse(profiles.listProfiles(true).isEmpty());
            for (var profile : profiles.listProfiles(true)) {
                assertEquals(profile, profiles.findAccount(profile.getChain(), profile.getNetwork()).orElseThrow());
                assertEquals(profile, profiles.findById(profile.getId()).orElseThrow());
                assertEquals(profile, profiles.findByChainAndNetwork(profile.getChain(), profile.getNetwork()).orElseThrow());
                assertNotNull(profile.getCreatedAt());
                assertNotNull(profile.getUpdatedAt());
            }
            assertTrue(profiles.listProfiles(false).stream().anyMatch(profile -> !profile.getEnabled()));
            var assets = new ChainAssetRepository(jdbc);
            assets.upsert(ChainAsset.builder().chain("ETH").symbol("TYPED_TEST").assetKind("ERC20")
                    .contractAddress("0x" + "12".repeat(20)).decimals(6).nativeAsset(false).active(true)
                    .minTransfer(new BigDecimal("1234567")).minWithdraw(new BigDecimal("7654321"))
                    .createdAt(Instant.now()).updatedAt(Instant.now()).build());
            var asset = assets.findActiveDefinition("eth", "typed_test").orElseThrow();
            assertTrue(asset.getId() > 0);
            assertEquals(6, asset.getDecimals());
            assertEquals("ERC20", asset.getAssetKind());
            // Asset thresholds are stored in atomic units, not display decimal amounts.
            assertEquals(0, new BigDecimal("1234567").compareTo(asset.getMinTransfer()));
            assertNotNull(asset.getCreatedAt());
            assertEquals(1, assets.countActiveNativeAssets("eth"));
            assets.updateActive("ETH", "TYPED_TEST", false);
            assertTrue(assets.findActiveDefinition("ETH", "TYPED_TEST").isEmpty());
        });
    }

    @Test
    void typedTokenAndRpcQueriesPreserveAliasesFiltersAndPriority() {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var profile = new ChainProfileRepository(jdbc).findAccountByChain("ETH").orElseThrow();
            var tokens = new TokenConfigRepository(jdbc);
            long id = tokens.insert("ETH", profile.getNetwork(), "TYPED_TEST", "ERC20", "0x" + "12".repeat(20),
                    "AliasBase58", "0x123456", 6, true, BigDecimal.ZERO, BigDecimal.ZERO,
                    true, BigDecimal.ZERO, "NATIVE", 3);
            var token = tokens.findDefinition("eth", profile.getNetwork(), "typed_test").orElseThrow();
            assertEquals(id, token.getId());
            assertEquals("0x" + "12".repeat(20), token.getContractAddress());
            assertEquals("ERC20", token.getStandard());
            assertEquals(6, token.getDecimals());
            assertEquals(token, tokens.findDefinitionByContract("ETH", profile.getNetwork(), "0x123456").orElseThrow());
            assertEquals(token, tokens.findDefinitionByContract("ETH", profile.getNetwork(), "aliasbase58").orElseThrow());
            assertTrue(tokens.listDefinitions("ETH", profile.getNetwork()).contains(token));
            tokens.updateEnabled(id, false, false);
            assertTrue(tokens.findDefinition("ETH", profile.getNetwork(), "TYPED_TEST").isEmpty());

            for (String purpose : List.of("all", "rpc", "scan")) {
                jdbc.update("""
                        insert into chain_rpc_node(chain, network, environment, node_label, purpose,
                            connection_type, rpc_url, auth_type, priority, min_request_interval_ms, enabled)
                        values ('ETH', ?, 'typed-runtime', ?, ?, 'HTTP_JSON_RPC',
                            'http://example.invalid', 'NONE', ?, 50, true)
                        """, profile.getNetwork(), "typed-" + purpose, purpose,
                        switch (purpose) { case "all" -> 1; case "scan" -> 2; default -> 3; });
            }
            var rpc = new ChainRpcNodeRepository(jdbc);
            var nodes = rpc.listRuntimeNodes("ETH", profile.getNetwork(), "TYPED-RUNTIME", "rpc");
            assertEquals(List.of("all", "rpc"), nodes.stream().map(node -> node.getPurpose()).toList());
            assertEquals(50, nodes.getFirst().getMinRequestIntervalMs());
            assertEquals(nodes.getFirst(), rpc.findById(nodes.getFirst().getId(), "ETH", profile.getNetwork()).orElseThrow());
            assertTrue(rpc.listByChain("ETH", profile.getNetwork()).contains(nodes.getFirst()));
            Instant checked = Instant.parse("2026-01-01T00:00:00Z");
            rpc.updateProbe(nodes.getFirst().getId(), checked, 17, 200, null);
            var checkedNode = rpc.findById(nodes.getFirst().getId(), "ETH", profile.getNetwork()).orElseThrow();
            assertEquals(checked, checkedNode.getLastCheckedAt());
            assertEquals(17L, checkedNode.getLastLatencyMs());
            assertEquals(200, checkedNode.getLastHttpStatus());
            assertNotNull(checkedNode.getCreatedAt());
            assertNotNull(checkedNode.getUpdatedAt());
            assertEquals(List.of("all", "rpc", "scan"), rpc
                    .listRuntimeNodes("ETH", profile.getNetwork(), "typed-runtime", null).stream()
                    .map(node -> node.getPurpose()).toList());
            assertTrue(rpc.listRuntimeNodes("ETH", profile.getNetwork(), "other-environment", "rpc").isEmpty());
        });
    }

    @Test
    void realRuntimeSwitchesCannotBeBypassedByTheEvmScanner() {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update("update chain_profile set scan_enabled = true where chain = 'ETH' and enabled = true");
            var settings = new com.surprising.wallet.repository.WalletSystemConfigRepository(jdbc);
            var runtime = new com.surprising.wallet.service.WalletRuntimeConfigService(
                    new ChainProfileRepository(jdbc), settings);
            var scanner = com.surprising.wallet.testutil.EvmScanTestSupport.scanner(jdbc,
                    com.surprising.wallet.common.chain.ChainType.ETH, "http://127.0.0.1:1", 1);
            settings.upsertBoolean("global.all.enabled", true);
            settings.upsertBoolean("global.scan.enabled", false);
            assertFalse(runtime.isTaskEnabled("ETH", "scan"));
            var failure = assertThrows(IllegalStateException.class, () -> scanner.scanAndCreditNative(
                    com.surprising.wallet.common.chain.ChainType.ETH, "ETH", "http://127.0.0.1:1", 1, 1L));
            assertTrue(failure.getMessage().contains("disabled"));
            settings.upsertBoolean("global.scan.enabled", true);
            assertTrue(runtime.isTaskEnabled("ETH", "scan"));
            jdbc.update("update chain_profile set scan_enabled = false where chain = 'ETH' and enabled = true");
            assertFalse(runtime.isTaskEnabled("ETH", "scan"));
            assertThrows(IllegalStateException.class, () -> scanner.scanAndCreditNative(
                    com.surprising.wallet.common.chain.ChainType.ETH, "ETH", "http://127.0.0.1:1", 1, 1L));
            jdbc.update("update chain_profile set scan_enabled = true where chain = 'ETH' and enabled = true");
            jdbc.update("update wallet_system_config set enabled = false where config_key = 'global.all.enabled'");
            assertFalse(runtime.isGlobalEnabled());
            assertThrows(IllegalStateException.class, () -> scanner.scanAndCreditNative(
                    com.surprising.wallet.common.chain.ChainType.ETH, "ETH", "http://127.0.0.1:1", 1, 1L));
        });
    }
    @Test
    void runtimeTokenManagementAndOverviewShareDefaultsDisabledRowsAndTaskRules() {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var profiles = new ChainProfileRepository(jdbc);
            var settings = new WalletSystemConfigRepository(jdbc);
            var tokens = new TokenConfigRepository(jdbc);
            var assets = new ChainAssetRepository(jdbc);
            var rpc = new ChainRpcNodeRepository(jdbc);
            var audits = new CustodyAuditRepository(jdbc);
            var runtime = new WalletRuntimeConfigService(profiles, settings);
            String network = profiles.findAccountByChain("ETH").orElseThrow().getNetwork();
            long token = tokens.insert("ETH", network, "POLICY_TEST", "ERC20", "0x" + "12".repeat(20),
                    null, null, 6, true, BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.ZERO, "NATIVE", 1);
            assets.upsertNonNative("ETH", "POLICY_TEST", "ERC20", "0x" + "12".repeat(20), 6, true,
                    BigDecimal.ZERO, BigDecimal.ZERO);
            var management = WalletConfigTestSupport.tokens(jdbc, runtime);
            var keys = org.mockito.Mockito.mock(com.surprising.wallet.common.key.WalletKeyMaterialProvider.class);
            org.mockito.Mockito.when(keys.isConfigured()).thenReturn(true);
            var overview = new WalletConfigOverviewService(settings, profiles, tokens, assets, rpc, audits,
                    keys, "test", runtime);
            for (String task : List.of("scan", "withdraw", "collection", "transfer")) {
                for (String key : List.of("all", "scan", "withdraw", "collection", "transfer")) {
                    settings.upsertBoolean("global." + key + ".enabled", true);
                }
                jdbc.update("""
                        update chain_profile set scan_enabled=?, withdraw_enabled=?,
                            collection_enabled=?, transfer_enabled=? where chain='ETH' and network=?
                        """, task.equals("scan"), task.equals("withdraw"), task.equals("collection"),
                        task.equals("transfer"), network);
                tokens.updateEnabled(token, true, false);
                assertPolicyViews(runtime, management, overview, network, task, true, !task.equals("collection"));
                if (task.equals("collection")) {
                    tokens.updateEnabled(token, true, true);
                    assertPolicyViews(runtime, management, overview, network, task, true, true);
                }
                jdbc.update("update wallet_system_config set enabled=false where config_key=?",
                        WalletRuntimeConfigService.globalTaskKey(task));
                assertPolicyViews(runtime, management, overview, network, task, false, false);
                settings.upsertBoolean(WalletRuntimeConfigService.globalTaskKey(task), true);
                settings.upsertBoolean(WalletRuntimeConfigService.GLOBAL_WALLET_ENABLED, false);
                assertPolicyViews(runtime, management, overview, network, task, false, false);
                var blocked = management.listTokens(CustodyTestSupport.platformAdmin(), "POLICY_TEST", "ETH",
                        network, "", null, null).getFirst();
                assertTrue(blocked.blockers().contains("Wallet master switch is off."));
                assertFalse(blocked.blockers().contains("No wallet task is effectively enabled for this chain."));
                jdbc.update("delete from wallet_system_config where config_key in (?, ?)",
                        WalletRuntimeConfigService.GLOBAL_WALLET_ENABLED, WalletRuntimeConfigService.globalTaskKey(task));
                assertPolicyViews(runtime, management, overview, network, task, true, true);
            }
            jdbc.update("update chain_profile set enabled=false where chain='ETH' and network=?", network);
            assertPolicyViews(runtime, management, overview, network, "transfer", false, false);
            assertTrue(runtime.isGlobalTaskEnabled("unregistered-task"));
            settings.upsertBoolean("global.unregistered-task.enabled", false);
            assertFalse(runtime.isGlobalTaskEnabled("unregistered-task"));
        });
    }

    private static void assertPolicyViews(WalletRuntimeConfigService runtime,
            WalletTokenConfigService management, WalletConfigOverviewService overview,
            String network, String task, boolean expectedTask, boolean expectedToken) {
        assertEquals(expectedTask, runtime.isTaskEnabled("ETH", task), task + " runtime");
        var effective = overview.summary(CustodyTestSupport.platformAdmin()).chains().stream()
                .filter(row -> row.chain().equals("ETH") && row.network().equals(network))
                .findFirst().orElseThrow().effectiveTasks();
        boolean shown = switch (task) {
            case "scan" -> effective.scanEnabled();
            case "withdraw" -> effective.withdrawEnabled();
            case "collection" -> effective.collectionEnabled();
            case "transfer" -> effective.transferEnabled();
            default -> throw new AssertionError(task);
        };
        assertEquals(expectedTask, shown, task + " overview");
        assertEquals(expectedToken, management.listTokens(CustodyTestSupport.platformAdmin(), "POLICY_TEST", "ETH",
                network, "", null, null).getFirst().effectiveEnabled(), task + " token management");
    }

}
