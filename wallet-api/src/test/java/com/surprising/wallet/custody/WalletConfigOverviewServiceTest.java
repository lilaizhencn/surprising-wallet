package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.repository.CustodyRepository;
import com.surprising.wallet.service.WalletConfigOverviewService.SummaryView;
import com.surprising.wallet.service.WalletConfigOverviewService.UpdateGlobalSwitchesCommand;
import com.surprising.wallet.service.WalletConfigOverviewService;
/**
 * 验证 {@code WalletConfigOverviewServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class WalletConfigOverviewServiceTest {
    /**
     * 保存 {@code PLATFORM_ADMIN}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final CustodyPrincipal PLATFORM_ADMIN = new CustodyPrincipal(
            CustodyPrincipal.ActorType.PLATFORM_USER,
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            null,
            null,
            "PLATFORM_ADMIN",
            Set.of("*"));

    /**
     * 验证 {@code testEnvironmentAllowsDevAndTestProfilesWithOneEnabledNetwork} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void testEnvironmentAllowsDevAndTestProfilesWithOneEnabledNetwork() {
        FakeJdbcTemplate jdbc = configuredDatabase();
        WalletConfigOverviewService service = service(jdbc, "test2", true, new FakeAuditRepository());

        WalletConfigOverviewService.SummaryView summary = service.summary(PLATFORM_ADMIN);

        assertFalse(summary.production());
        assertEquals(1, summary.statistics().enabledChainCount());
        assertEquals(1, summary.statistics().enabledNetworkCount());
        assertTrue(summary.anomalies().stream()
                .noneMatch(row -> row.code().equals("MULTIPLE_ENABLED_NETWORKS")));
        assertEquals("ACTIVE", summary.chains().get(0).status());
        assertEquals("DISABLED", summary.chains().get(1).status());
    }

    /**
     * 验证 {@code productionReportsMultipleAndNonProductionNetworks} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void productionReportsMultipleAndNonProductionNetworks() {
        FakeJdbcTemplate jdbc = configuredDatabase();
        jdbc.profileRows.replaceAll(row -> {
            Map<String, Object> enabled = new LinkedHashMap<>(row);
            enabled.put("enabled", true);
            return enabled;
        });
        WalletConfigOverviewService service = service(jdbc, "prod", true, new FakeAuditRepository());

        WalletConfigOverviewService.SummaryView summary = service.summary(PLATFORM_ADMIN);

        assertTrue(summary.production());
        assertTrue(summary.anomalies().stream()
                .anyMatch(row -> row.code().equals("MULTIPLE_ENABLED_NETWORKS")));
        assertEquals(2, summary.anomalies().stream()
                .filter(row -> row.code().equals("NON_PRODUCTION_NETWORK"))
                .count());
        assertTrue(summary.chains().stream().allMatch(row -> row.status().equals("BLOCKED")));
    }

    /**
     * 验证 {@code updatesAllGlobalSwitchesAndWritesAudit} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void updatesAllGlobalSwitchesAndWritesAudit() {
        FakeJdbcTemplate jdbc = configuredDatabase();
        FakeAuditRepository audit = new FakeAuditRepository();
        WalletConfigOverviewService service = service(jdbc, "test2", true, audit);

        WalletConfigOverviewService.SummaryView summary = service.updateGlobalSwitches(
                PLATFORM_ADMIN,
                new WalletConfigOverviewService.UpdateGlobalSwitchesCommand(
                        true, false, true, false, true),
                "127.0.0.1");

        assertFalse(summary.globalSwitches().scanEnabled());
        assertFalse(summary.globalSwitches().collectionEnabled());
        assertEquals(5, jdbc.switchUpdates);
        assertEquals("WALLET_GLOBAL_SWITCHES.UPDATE", audit.action);
        assertEquals("127.0.0.1", audit.sourceIp);
        assertEquals("{\"walletEnabled\":true,\"scanEnabled\":false,\"withdrawEnabled\":true,"
                + "\"collectionEnabled\":false,\"transferEnabled\":true}", audit.details);
    }

    /**
     * 验证 {@code service} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static WalletConfigOverviewService service(FakeJdbcTemplate jdbc,
                                                       String environment,
                                                       boolean keysetConfigured,
                                                       CustodyRepository audit) {
        return new WalletConfigOverviewService(jdbc, audit, () -> keysetConfigured, environment);
    }

    /**
     * 验证 {@code configuredDatabase} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static FakeJdbcTemplate configuredDatabase() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate();
        jdbc.systemRows.add(switchRow("global.all.enabled", true));
        jdbc.systemRows.add(switchRow("global.scan.enabled", true));
        jdbc.systemRows.add(switchRow("global.withdraw.enabled", true));
        jdbc.systemRows.add(switchRow("global.collection.enabled", true));
        jdbc.systemRows.add(switchRow("global.transfer.enabled", true));
        jdbc.profileRows.add(profileRow(1L, "ETH", "devnet", true));
        jdbc.profileRows.add(profileRow(2L, "ETH", "testnet", false));
        jdbc.rpcRows.add(rpcRow("ETH", "devnet", "test2"));
        jdbc.rpcRows.add(rpcRow("ETH", "testnet", "test2"));
        jdbc.rpcRows.add(rpcRow("ETH", "devnet", "prod"));
        jdbc.rpcRows.add(rpcRow("ETH", "testnet", "prod"));
        return jdbc;
    }

    /**
     * 验证 {@code switchRow} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Map<String, Object> switchRow(String key, boolean value) {
        return map(
                "config_key", key,
                "config_value", Boolean.toString(value),
                "enabled", true);
    }

    /**
     * 验证 {@code profileRow} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Map<String, Object> profileRow(long id, String chain, String network, boolean enabled) {
        return map(
                "id", id,
                "chain", chain,
                "network", network,
                "family", "evm",
                "enabled", enabled,
                "scan_enabled", true,
                "withdraw_enabled", true,
                "collection_enabled", true,
                "transfer_enabled", true);
    }

    /**
     * 验证 {@code rpcRow} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Map<String, Object> rpcRow(String chain, String network, String environment) {
        return map(
                "chain", chain,
                "network", network,
                "environment", environment,
                "enabled", true);
    }

    /**
     * 验证 {@code map} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }

    /**
     * 测试替身 {@code FakeJdbcTemplate}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeJdbcTemplate extends JdbcTemplate {
        /**
         * 保存 {@code systemRows}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<Map<String, Object>> systemRows = new ArrayList<>();
        /**
         * 保存 {@code profileRows}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<Map<String, Object>> profileRows = new ArrayList<>();
        /**
         * 保存 {@code tokenRows}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final List<Map<String, Object>> tokenRows = new ArrayList<>();
        /**
         * 保存 {@code assetRows}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final List<Map<String, Object>> assetRows = new ArrayList<>();
        /**
         * 保存 {@code rpcRows}，用于访问当前测试所依赖的仓储、客户端或服务。
         */
        private final List<Map<String, Object>> rpcRows = new ArrayList<>();
        /**
         * 保存 {@code switchUpdates}，用于记录测试时间边界或审计时间。
         */
        private int switchUpdates;

        /**
         * 验证 {@code queryForList} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            if (sql.contains("from wallet_system_config")) {
                return List.copyOf(systemRows);
            }
            if (sql.contains("from chain_profile")) {
                return List.copyOf(profileRows);
            }
            if (sql.contains("from token_config")) {
                return List.copyOf(tokenRows);
            }
            if (sql.contains("from chain_asset")) {
                return List.copyOf(assetRows);
            }
            if (sql.contains("from chain_rpc_node")) {
                return List.copyOf(rpcRows);
            }
            throw new AssertionError("unexpected query: " + sql);
        }

        /**
         * 验证 {@code update} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int update(String sql, Object... args) {
            if (!sql.contains("wallet_system_config")) {
                throw new AssertionError("unexpected update: " + sql);
            }
            String key = String.valueOf(args[0]);
            String value = String.valueOf(args[1]);
            systemRows.removeIf(row -> key.equals(row.get("config_key")));
            systemRows.add(map("config_key", key, "config_value", value, "enabled", true));
            switchUpdates++;
            return 1;
        }
    }

    /**
     * 测试替身 {@code FakeAuditRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeAuditRepository extends CustodyRepository {
        /**
         * 保存 {@code action}，用于承载当前测试夹具的配置或运行数据。
         */
        private String action;
        /**
         * 保存 {@code sourceIp}，用于承载当前测试夹具的配置或运行数据。
         */
        private String sourceIp;
        /**
         * 保存 {@code details}，用于承载当前测试夹具的配置或运行数据。
         */
        private String details;

        /**
         * 验证 {@code FakeAuditRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeAuditRepository() {
            super(null);
        }

        /**
         * 验证 {@code audit} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public void audit(UUID tenantId, String actorType, String actorId, String action,
                          String resourceType, String resourceId, String sourceIp, String detailsJson) {
            this.action = action;
            this.sourceIp = sourceIp;
            this.details = detailsJson;
        }
    }
}
