package com.surprising.wallet.custody;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
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
            var token = tokens.findDefinition("eth", "typed_test").orElseThrow();
            assertEquals(id, token.getId());
            assertEquals("0x" + "12".repeat(20), token.getContractAddress());
            assertEquals("ERC20", token.getStandard());
            assertEquals(6, token.getDecimals());
            assertEquals(token, tokens.findDefinitionByContract("ETH", "0x123456").orElseThrow());
            assertEquals(token, tokens.findDefinitionByContract("ETH", "aliasbase58").orElseThrow());
            assertTrue(tokens.listDefinitions("ETH").contains(token));
            tokens.updateEnabled(id, false, false);
            assertTrue(tokens.findDefinition("ETH", "TYPED_TEST").isEmpty());

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
            assertEquals(List.of("all", "rpc", "scan"), rpc
                    .listRuntimeNodes("ETH", profile.getNetwork(), "typed-runtime", null).stream()
                    .map(node -> node.getPurpose()).toList());
            assertTrue(rpc.listRuntimeNodes("ETH", profile.getNetwork(), "other-environment", "rpc").isEmpty());
        });
    }
}
