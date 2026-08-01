package com.surprising.wallet.service;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.config.WalletEnvironmentPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.repository.CustodyRepository;

/**
 * 钱包配置概览服务，提供环境策略、RPC 策略、链配置的健康检查总览。
 *
 * <p>用于 Console 平台的系统状态页面，汇总环境名称、RPC 节点数、
 * 链启用状态、密钥配置状态等信息。
 */
@Service
public class WalletConfigOverviewService {
    /**
     * 定义 {@code SWITCH_KEYS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Map<String, String> SWITCH_KEYS = Map.of(
            "wallet", "global.all.enabled",
            "scan", "global.scan.enabled",
            "withdraw", "global.withdraw.enabled",
            "collection", "global.collection.enabled",
            "transfer", "global.transfer.enabled");
    /**
     * 保存 {@code jdbc}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final JdbcTemplate jdbc;
    /**
     * 保存 {@code custodyRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final CustodyRepository custodyRepository;
    /**
     * 保存 {@code keysetConfigured}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final BooleanSupplier keysetConfigured;
    /**
     * 保存 {@code environment}，用于承载当前对象的运行配置或业务数据。
     */
    private final String environment;

    /**
     * 构造 {@code WalletConfigOverviewService}，初始化该组件运行所需的状态和依赖。
     */
    @Autowired
    public WalletConfigOverviewService(JdbcTemplate jdbc,
                                       CustodyRepository custodyRepository,
                                       WalletKeyMaterialProvider keyMaterial,
                                       @Value("${sw.app.env.name:dev}")
                                       String environment) {
        this.jdbc = jdbc;
        this.custodyRepository = custodyRepository;
        this.keysetConfigured = keyMaterial::isConfigured;
        this.environment = normalizeEnvironment(environment);
    }

    /**
     * 构造 {@code WalletConfigOverviewService}，初始化该组件运行所需的状态和依赖。
     */
    public WalletConfigOverviewService(JdbcTemplate jdbc,
                                CustodyRepository custodyRepository,
                                BooleanSupplier keysetConfigured,
                                String environment) {
        this.jdbc = jdbc;
        this.custodyRepository = custodyRepository;
        this.keysetConfigured = keysetConfigured;
        this.environment = normalizeEnvironment(environment);
    }
    /**
     * 执行 {@code summary} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public SummaryView summary(CustodyPrincipal actor) {
        requirePlatformAdmin(actor);
        GlobalSwitches switches = loadSwitches();
        boolean keysetConfigured = this.keysetConfigured.getAsBoolean();
        List<ProfileRow> profiles = loadProfiles();
        List<TokenRow> tokens = loadTokens();
        List<AssetRow> assets = loadAssets();
        List<RpcRow> rpcNodes = loadRpcNodes();
        boolean production = WalletEnvironmentPolicy.isProduction(environment);

        List<AnomalyView> anomalies = findAnomalies(
                keysetConfigured, production, profiles, tokens, assets, rpcNodes);
        Map<String, Integer> tokenCounts = enabledTokenCounts(tokens);
        Map<String, Integer> rpcCounts = enabledRpcCounts(rpcNodes);
        Map<String, Long> enabledNetworksByChain = profiles.stream()
                .filter(ProfileRow::enabled)
                .collect(Collectors.groupingBy(
                        profile -> normalize(profile.chain()),
                        Collectors.counting()));

        List<ChainStatusView> chains = profiles.stream()
                .map(profile -> chainStatus(
                        profile,
                        switches,
                        keysetConfigured,
                        production,
                        enabledNetworksByChain.getOrDefault(normalize(profile.chain()), 0L),
                        tokenCounts.getOrDefault(profileKey(profile.chain(), profile.network()), 0),
                        rpcCounts.getOrDefault(profileKey(profile.chain(), profile.network()), 0)))
                .toList();

        int enabledChainCount = (int) profiles.stream()
                .filter(ProfileRow::enabled)
                .map(profile -> normalize(profile.chain()))
                .distinct()
                .count();
        int enabledNetworkCount = (int) profiles.stream().filter(ProfileRow::enabled).count();
        int enabledTokenCount = (int) tokens.stream().filter(TokenRow::enabled).count();
        int enabledRpcNodeCount = (int) rpcNodes.stream()
                .filter(RpcRow::enabled)
                .filter(this::currentEnvironment)
                .count();

        return new SummaryView(
                environment,
                production,
                keysetConfigured,
                switches,
                new StatisticsView(
                        profiles.size(), enabledChainCount, enabledNetworkCount,
                        enabledTokenCount, enabledRpcNodeCount, anomalies.size()),
                chains,
                anomalies,
                Instant.now());
    }

    /**
     * 设置或更新 {@code updateGlobalSwitches} 对应的状态，并保持相关业务字段一致。
     */
    @Transactional
    public SummaryView updateGlobalSwitches(CustodyPrincipal actor,
                                            UpdateGlobalSwitchesCommand command,
                                            String sourceIp) {
        requirePlatformAdmin(actor);
        if (command == null
                || command.walletEnabled() == null
                || command.scanEnabled() == null
                || command.withdrawEnabled() == null
                || command.collectionEnabled() == null
                || command.transferEnabled() == null) {
            throw new IllegalArgumentException("all five global switches are required");
        }
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put(SWITCH_KEYS.get("wallet"), command.walletEnabled());
        values.put(SWITCH_KEYS.get("scan"), command.scanEnabled());
        values.put(SWITCH_KEYS.get("withdraw"), command.withdrawEnabled());
        values.put(SWITCH_KEYS.get("collection"), command.collectionEnabled());
        values.put(SWITCH_KEYS.get("transfer"), command.transferEnabled());
        values.forEach(this::upsertSwitch);

        String details = String.format(Locale.ROOT,
                "{\"walletEnabled\":%s,\"scanEnabled\":%s,"
                        + "\"withdrawEnabled\":%s,\"collectionEnabled\":%s,"
                        + "\"transferEnabled\":%s}",
                command.walletEnabled(), command.scanEnabled(), command.withdrawEnabled(),
                command.collectionEnabled(), command.transferEnabled());
        custodyRepository.audit(null, "PLATFORM_USER", actor.actorId().toString(),
                "WALLET_GLOBAL_SWITCHES.UPDATE", "WALLET_GLOBAL_SWITCHES", "global",
                sourceIp, details);
        return summary(actor);
    }
    /**
     * 获取或查询 {@code loadSwitches} 对应的数据，供调用方读取当前状态。
     */
    private GlobalSwitches loadSwitches() {
        Map<String, Boolean> values = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select config_key, config_value, enabled
                  from wallet_system_config
                 where config_key in (
                    'global.all.enabled',
                    'global.scan.enabled',
                    'global.withdraw.enabled',
                    'global.collection.enabled',
                    'global.transfer.enabled'
                 )
                """)) {
            boolean enabled = booleanValue(row.get("enabled"), true);
            values.put(stringValue(row.get("config_key")),
                    enabled && Boolean.parseBoolean(stringValue(row.get("config_value"))));
        }
        return new GlobalSwitches(
                values.getOrDefault(SWITCH_KEYS.get("wallet"), true),
                values.getOrDefault(SWITCH_KEYS.get("scan"), true),
                values.getOrDefault(SWITCH_KEYS.get("withdraw"), true),
                values.getOrDefault(SWITCH_KEYS.get("collection"), true),
                values.getOrDefault(SWITCH_KEYS.get("transfer"), true));
    }
    /**
     * 获取或查询 {@code loadProfiles} 对应的数据，供调用方读取当前状态。
     */
    private List<ProfileRow> loadProfiles() {
        return jdbc.queryForList("""
                select id, chain, network, family, enabled,
                       scan_enabled, withdraw_enabled, collection_enabled, transfer_enabled
                  from chain_profile
                 order by chain, network
                """).stream().map(row -> new ProfileRow(
                longValue(row.get("id")),
                stringValue(row.get("chain")),
                stringValue(row.get("network")),
                stringValue(row.get("family")),
                booleanValue(row.get("enabled"), false),
                booleanValue(row.get("scan_enabled"), false),
                booleanValue(row.get("withdraw_enabled"), false),
                booleanValue(row.get("collection_enabled"), false),
                booleanValue(row.get("transfer_enabled"), false))).toList();
    }
    /**
     * 获取或查询 {@code loadTokens} 对应的数据，供调用方读取当前状态。
     */
    private List<TokenRow> loadTokens() {
        return jdbc.queryForList("""
                select chain, network, symbol, enabled,
                       coalesce(contract_address, contract_address_base58, contract_address_hex) as contract_address
                  from token_config
                 order by chain, network, symbol
                """).stream().map(row -> new TokenRow(
                stringValue(row.get("chain")),
                stringValue(row.get("network")),
                stringValue(row.get("symbol")),
                stringValue(row.get("contract_address")),
                booleanValue(row.get("enabled"), false))).toList();
    }
    /**
     * 获取或查询 {@code loadAssets} 对应的数据，供调用方读取当前状态。
     */
    private List<AssetRow> loadAssets() {
        return jdbc.queryForList("""
                select chain, symbol, contract_address, active
                  from chain_asset
                 where native_asset = false
                 order by chain, symbol
                """).stream().map(row -> new AssetRow(
                stringValue(row.get("chain")),
                stringValue(row.get("symbol")),
                stringValue(row.get("contract_address")),
                booleanValue(row.get("active"), false))).toList();
    }
    /**
     * 获取或查询 {@code loadRpcNodes} 对应的数据，供调用方读取当前状态。
     */
    private List<RpcRow> loadRpcNodes() {
        return jdbc.queryForList("""
                select chain, network, environment, enabled
                  from chain_rpc_node
                 order by chain, network, environment, priority, id
                """).stream().map(row -> new RpcRow(
                stringValue(row.get("chain")),
                stringValue(row.get("network")),
                stringValue(row.get("environment")),
                booleanValue(row.get("enabled"), false))).toList();
    }

    /**
     * 获取或查询 {@code findAnomalies} 对应的数据，供调用方读取当前状态。
     */
    private List<AnomalyView> findAnomalies(boolean keysetConfigured,
                                            boolean production,
                                            List<ProfileRow> profiles,
                                            List<TokenRow> tokens,
                                            List<AssetRow> assets,
                                            List<RpcRow> rpcNodes) {
        List<AnomalyView> anomalies = new ArrayList<>();
        if (!keysetConfigured) {
            anomalies.add(new AnomalyView(
                    "KEYSET_NOT_CONFIGURED", "ERROR", null, null,
                    "Wallet keyset is not configured."));
        }

        Map<String, List<ProfileRow>> enabledProfiles = profiles.stream()
                .filter(ProfileRow::enabled)
                .collect(Collectors.groupingBy(profile -> normalize(profile.chain())));
        enabledProfiles.forEach((chain, rows) -> {
            if (rows.size() > 1) {
                anomalies.add(new AnomalyView(
                        "MULTIPLE_ENABLED_NETWORKS", "ERROR", chain, null,
                        "Only one network can be enabled per chain at a time: "
                                + rows.stream().map(ProfileRow::network).collect(Collectors.joining(", "))));
            }
        });

        Set<String> enabledProfileKeys = profiles.stream()
                .filter(ProfileRow::enabled)
                .map(profile -> profileKey(profile.chain(), profile.network()))
                .collect(Collectors.toSet());
        Set<String> enabledRpcKeys = rpcNodes.stream()
                .filter(RpcRow::enabled)
                .filter(this::currentEnvironment)
                .map(rpc -> profileKey(rpc.chain(), rpc.network()))
                .collect(Collectors.toSet());
        for (ProfileRow profile : profiles) {
            if (!profile.enabled()) {
                continue;
            }
            if (production && !WalletEnvironmentPolicy.isProductionNetwork(profile.network())) {
                anomalies.add(new AnomalyView(
                        "NON_PRODUCTION_NETWORK", "ERROR", profile.chain(), profile.network(),
                        "Production cannot enable a non-production network."));
            }
            if (!enabledRpcKeys.contains(profileKey(profile.chain(), profile.network()))) {
                anomalies.add(new AnomalyView(
                        "RPC_NODE_MISSING", "ERROR", profile.chain(), profile.network(),
                        "No enabled RPC node is configured for environment " + environment + "."));
            }
        }

        Map<String, AssetRow> activeAssets = assets.stream()
                .filter(AssetRow::active)
                .collect(Collectors.toMap(
                        asset -> assetKey(asset.chain(), asset.symbol()),
                        asset -> asset,
                        (left, right) -> left));
        Set<String> enabledTokenAssets = new LinkedHashSet<>();
        for (TokenRow token : tokens) {
            if (!token.enabled()) {
                continue;
            }
            String tokenAssetKey = assetKey(token.chain(), token.symbol());
            enabledTokenAssets.add(tokenAssetKey);
            if (token.network().isBlank()) {
                anomalies.add(new AnomalyView(
                        "TOKEN_NETWORK_MISSING", "WARNING", token.chain(), null,
                        token.symbol() + " does not declare its network."));
            } else if (!enabledProfileKeys.contains(profileKey(token.chain(), token.network()))) {
                anomalies.add(new AnomalyView(
                        "TOKEN_NETWORK_DISABLED", "ERROR", token.chain(), token.network(),
                        token.symbol() + " has no matching enabled chain profile."));
            }
            AssetRow asset = activeAssets.get(tokenAssetKey);
            if (asset == null) {
                anomalies.add(new AnomalyView(
                        "TOKEN_ASSET_MISSING", "ERROR", token.chain(), token.network(),
                        token.symbol() + " has no matching active chain asset."));
            } else if (!sameContract(token.contractAddress(), asset.contractAddress())) {
                anomalies.add(new AnomalyView(
                        "TOKEN_CONTRACT_MISMATCH", "ERROR", token.chain(), token.network(),
                        token.symbol() + " contract differs from chain_asset."));
            }
        }
        for (AssetRow asset : activeAssets.values()) {
            if (!enabledTokenAssets.contains(assetKey(asset.chain(), asset.symbol()))) {
                anomalies.add(new AnomalyView(
                        "ASSET_TOKEN_MISSING", "ERROR", asset.chain(), null,
                        asset.symbol() + " has no matching enabled token configuration."));
            }
        }
        return List.copyOf(anomalies);
    }

    /**
     * 获取或查询 {@code chainStatus} 对应的数据，并向调用方返回当前业务状态。
     */
    private ChainStatusView chainStatus(ProfileRow profile,
                                        GlobalSwitches global,
                                        boolean keysetConfigured,
                                        boolean production,
                                        long enabledNetworkCount,
                                        int tokenCount,
                                        int rpcNodeCount) {
        TaskSwitches configured = new TaskSwitches(
                profile.scanEnabled(), profile.withdrawEnabled(),
                profile.collectionEnabled(), profile.transferEnabled());
        TaskSwitches effective = new TaskSwitches(
                effective(global.walletEnabled(), global.scanEnabled(), profile.enabled(), profile.scanEnabled()),
                effective(global.walletEnabled(), global.withdrawEnabled(), profile.enabled(), profile.withdrawEnabled()),
                effective(global.walletEnabled(), global.collectionEnabled(), profile.enabled(), profile.collectionEnabled()),
                effective(global.walletEnabled(), global.transferEnabled(), profile.enabled(), profile.transferEnabled()));
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (!profile.enabled()) {
            blockers.add("Chain profile is disabled.");
        } else {
            if (!keysetConfigured) {
                blockers.add("Wallet keyset is not configured.");
            }
            if (rpcNodeCount == 0) {
                blockers.add("No enabled RPC node for environment " + environment + ".");
            }
            if (enabledNetworkCount > 1) {
                blockers.add("Only one network can be enabled per chain at a time.");
            }
            if (production && !WalletEnvironmentPolicy.isProductionNetwork(profile.network())) {
                blockers.add("This network is not allowed in production.");
            }
            if (!global.walletEnabled()) {
                blockers.add("Wallet master switch is off.");
            }
            if (profile.scanEnabled() && !global.scanEnabled()) {
                blockers.add("Global scan switch is off.");
            }
            if (profile.withdrawEnabled() && !global.withdrawEnabled()) {
                blockers.add("Global withdrawal switch is off.");
            }
            if (profile.collectionEnabled() && !global.collectionEnabled()) {
                blockers.add("Global collection switch is off.");
            }
            if (profile.transferEnabled() && !global.transferEnabled()) {
                blockers.add("Global transfer switch is off.");
            }
            if (!configured.anyEnabled()) {
                blockers.add("All chain task switches are off.");
            }
        }
        String status;
        if (!profile.enabled()) {
            status = "DISABLED";
        } else if (!blockers.isEmpty()) {
            status = "BLOCKED";
        } else if (!effective.anyEnabled()) {
            status = "INACTIVE";
        } else {
            status = "ACTIVE";
        }
        return new ChainStatusView(
                profile.id(), profile.chain(), profile.network(), profile.family(),
                profile.enabled(), configured, effective, tokenCount, rpcNodeCount,
                status, List.copyOf(blockers));
    }
    /**
     * 写入或更新 {@code enabledTokenCounts} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    private Map<String, Integer> enabledTokenCounts(List<TokenRow> tokens) {
        Map<String, Integer> counts = new HashMap<>();
        tokens.stream().filter(TokenRow::enabled).forEach(token ->
                counts.merge(profileKey(token.chain(), token.network()), 1, Integer::sum));
        return counts;
    }
    /**
     * 写入或更新 {@code enabledRpcCounts} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    private Map<String, Integer> enabledRpcCounts(List<RpcRow> nodes) {
        Map<String, Integer> counts = new HashMap<>();
        nodes.stream().filter(RpcRow::enabled).filter(this::currentEnvironment).forEach(node ->
                counts.merge(profileKey(node.chain(), node.network()), 1, Integer::sum));
        return counts;
    }
    /**
     * 获取或查询 {@code currentEnvironment} 对应的数据，并向调用方返回当前业务状态。
     */
    private boolean currentEnvironment(RpcRow row) {
        return environment.equalsIgnoreCase(row.environment());
    }
    /**
     * 写入或更新 {@code upsertSwitch} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    private void upsertSwitch(String key, boolean value) {
        jdbc.update("""
                insert into wallet_system_config(
                    config_key, config_value, value_type, enabled, remark)
                values (?, ?, 'boolean', true, 'Managed by the platform wallet configuration console')
                on conflict (config_key) do update set
                    config_value = excluded.config_value,
                    value_type = 'boolean',
                    enabled = true,
                    updated_at = now()
                """, key, Boolean.toString(value));
    }

    /**
     * 执行 {@code effective} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static boolean effective(boolean wallet, boolean globalTask,
                                     boolean profile, boolean profileTask) {
        return wallet && globalTask && profile && profileTask;
    }
    /**
     * 执行 {@code sameContract} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static boolean sameContract(String left, String right) {
        return !left.isBlank() && !right.isBlank() && left.trim().equalsIgnoreCase(right.trim());
    }
    /**
     * 获取或查询 {@code profileKey} 对应的数据，并向调用方返回当前业务状态。
     */
    private static String profileKey(String chain, String network) {
        return normalize(chain) + "|" + normalize(network);
    }
    /**
     * 获取或查询 {@code assetKey} 对应的数据，并向调用方返回当前业务状态。
     */
    private static String assetKey(String chain, String symbol) {
        return normalize(chain) + "|" + normalize(symbol);
    }
    /**
     * 转换或计算 {@code normalize} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    /**
     * 转换或计算 {@code normalizeEnvironment} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalizeEnvironment(String value) {
        return value == null || value.isBlank() ? "dev" : value.trim();
    }
    /**
     * 转换或计算 {@code stringValue} 对应的值，统一金额、格式和边界规则。
     */
    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
    /**
     * 转换或计算 {@code longValue} 对应的值，统一金额、格式和边界规则。
     */
    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(stringValue(value));
    }
    /**
     * 转换或计算 {@code booleanValue} 对应的值，统一金额、格式和边界规则。
     */
    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }
    /**
     * 校验 {@code requirePlatformAdmin} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requirePlatformAdmin(CustodyPrincipal actor) {
        if (actor == null || !actor.isPlatformAdmin()) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }

    public record UpdateGlobalSwitchesCommand(
            Boolean walletEnabled,
            Boolean scanEnabled,
            Boolean withdrawEnabled,
            Boolean collectionEnabled,
            Boolean transferEnabled) {
    }

    public record SummaryView(
            String environment,
            boolean production,
            boolean keysetConfigured,
            GlobalSwitches globalSwitches,
            StatisticsView statistics,
            List<ChainStatusView> chains,
            List<AnomalyView> anomalies,
            Instant generatedAt) {
    }

    public record GlobalSwitches(
            boolean walletEnabled,
            boolean scanEnabled,
            boolean withdrawEnabled,
            boolean collectionEnabled,
            boolean transferEnabled) {
    }

    public record StatisticsView(
            int configuredChainProfileCount,
            int enabledChainCount,
            int enabledNetworkCount,
            int enabledTokenCount,
            int enabledRpcNodeCount,
            int anomalyCount) {
    }

    public record TaskSwitches(
            boolean scanEnabled,
            boolean withdrawEnabled,
            boolean collectionEnabled,
            boolean transferEnabled) {
        /**
         * 执行 {@code anyEnabled} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        public boolean anyEnabled() {
            return scanEnabled || withdrawEnabled || collectionEnabled || transferEnabled;
        }
    }

    public record ChainStatusView(
            long profileId,
            String chain,
            String network,
            String family,
            boolean configuredEnabled,
            TaskSwitches configuredTasks,
            TaskSwitches effectiveTasks,
            int enabledTokenCount,
            int enabledRpcNodeCount,
            String status,
            List<String> blockers) {
    }

    public record AnomalyView(
            String code,
            String severity,
            String chain,
            String network,
            String message) {
    }

    private record ProfileRow(
            long id,
            String chain,
            String network,
            String family,
            boolean enabled,
            boolean scanEnabled,
            boolean withdrawEnabled,
            boolean collectionEnabled,
            boolean transferEnabled) {
    }

    private record TokenRow(
            String chain,
            String network,
            String symbol,
            String contractAddress,
            boolean enabled) {
    }

    private record AssetRow(
            String chain,
            String symbol,
            String contractAddress,
            boolean active) {
    }

    private record RpcRow(
            String chain,
            String network,
            String environment,
            boolean enabled) {
    }
}
