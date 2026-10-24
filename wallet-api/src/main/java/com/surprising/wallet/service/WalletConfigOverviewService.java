package com.surprising.wallet.service;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.TokenConfiguration;
import com.surprising.wallet.repository.ChainRpcNodeRepository.Overview;
import com.surprising.wallet.service.WalletRuntimeConfigService.GlobalSwitches;
import static com.surprising.wallet.service.WalletRuntimeConfigService.*;
import com.surprising.wallet.config.WalletEnvironmentPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.PageView;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WalletSystemConfigRepository;

/**
 * 钱包配置概览服务，提供环境策略、RPC 策略、链配置的健康检查总览。
 *
 * <p>用于 Console 平台的系统状态页面，汇总环境名称、RPC 节点数、
 * 链启用状态、密钥配置状态等信息。
 */
@Service
public class WalletConfigOverviewService {
    /** 全局系统配置仓储。 */
    private final WalletSystemConfigRepository systemConfigRepository;
    /** 链配置仓储。 */
    private final ChainProfileRepository chainProfileRepository;
    /** 代币配置仓储。 */
    private final TokenConfigRepository tokenConfigRepository;
    /** 链资产仓储。 */
    private final ChainAssetRepository chainAssetRepository;
    /** RPC 节点仓储。 */
    private final ChainRpcNodeRepository rpcNodeRepository;
    private final CustodyAuditRepository audits;
    /**
     * 提供密钥配置状态，概览接口不读取或返回私钥材料。
     */
    private final WalletKeyMaterialProvider keyMaterial;
    private final String environment;
    private final WalletRuntimeConfigService runtimeConfig;

    public WalletConfigOverviewService(WalletSystemConfigRepository systemConfigRepository,
                                       ChainProfileRepository chainProfileRepository,
                                       TokenConfigRepository tokenConfigRepository,
                                       ChainAssetRepository chainAssetRepository,
                                       ChainRpcNodeRepository rpcNodeRepository,
                                       CustodyAuditRepository audits,
                                       WalletKeyMaterialProvider keyMaterial,
                                       @Value("${sw.app.env.name:dev}")
                                       String environment, WalletRuntimeConfigService runtimeConfig) {
        this.systemConfigRepository = systemConfigRepository;
        this.chainProfileRepository = chainProfileRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.chainAssetRepository = chainAssetRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.audits = audits;
        this.keyMaterial = keyMaterial;
        this.environment = normalizeEnvironment(environment);
        this.runtimeConfig = runtimeConfig;
    }

    public PageView<Map<String, Object>> auditLog(CustodyPrincipal actor, int limit, int offset) {
        WalletConfigSupport.requirePlatformAdmin(actor);
        int pageSize = Math.min(Math.max(limit, 1), 200);
        int pageOffset = Math.max(offset, 0);
        return new PageView<>(
                audits.listPlatform(pageSize, pageOffset),
                audits.countPlatform(),
                pageSize, pageOffset);
    }


    public SummaryView summary(CustodyPrincipal actor) {
        requirePlatformAdmin(actor);
        GlobalSwitches switches = runtimeConfig.globalSwitches();
        boolean keysetConfigured = this.keyMaterial.isConfigured();
        List<AccountChainProfile> profiles = chainProfileRepository.listAll();
        List<TokenConfiguration> tokens = tokenConfigRepository.listAll();
        List<ChainAsset> assets = chainAssetRepository.listNonNativeOverview();
        List<Overview> rpcNodes = rpcNodeRepository.listOverview();
        boolean production = WalletEnvironmentPolicy.isProduction(environment);

        List<AnomalyView> anomalies = findAnomalies(
                keysetConfigured, production, profiles, tokens, assets, rpcNodes);
        Map<NetworkKey, Integer> tokenCounts = enabledTokenCounts(tokens);
        Map<NetworkKey, Integer> rpcCounts = enabledRpcCounts(rpcNodes);
        Map<String, Long> enabledNetworksByChain = profiles.stream()
                .filter(AccountChainProfile::getEnabled)
                .collect(Collectors.groupingBy(
                        profile -> normalize(profile.getChain()),
                        Collectors.counting()));

        List<ChainStatusView> chains = profiles.stream()
                .map(profile -> chainStatus(
                        profile,
                        switches,
                        keysetConfigured,
                        production,
                        enabledNetworksByChain.getOrDefault(normalize(profile.getChain()), 0L),
                        tokenCounts.getOrDefault(profileKey(profile.getChain(), profile.getNetwork()), 0),
                        rpcCounts.getOrDefault(profileKey(profile.getChain(), profile.getNetwork()), 0)))
                .toList();

        int enabledChainCount = (int) profiles.stream()
                .filter(AccountChainProfile::getEnabled)
                .map(profile -> normalize(profile.getChain()))
                .distinct()
                .count();
        int enabledNetworkCount = (int) profiles.stream().filter(AccountChainProfile::getEnabled).count();
        int enabledTokenCount = (int) tokens.stream().filter(TokenConfiguration::enabled).count();
        int enabledRpcNodeCount = (int) rpcNodes.stream()
                .filter(Overview::enabled)
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
        systemConfigRepository.upsertBoolean(GLOBAL_WALLET_ENABLED, command.walletEnabled());
        systemConfigRepository.upsertBoolean(globalTaskKey(TASK_SCAN), command.scanEnabled());
        systemConfigRepository.upsertBoolean(globalTaskKey(TASK_WITHDRAW), command.withdrawEnabled());
        systemConfigRepository.upsertBoolean(globalTaskKey(TASK_COLLECTION), command.collectionEnabled());
        systemConfigRepository.upsertBoolean(globalTaskKey(TASK_TRANSFER), command.transferEnabled());

        String details = String.format(Locale.ROOT,
                "{\"walletEnabled\":%s,\"scanEnabled\":%s,"
                        + "\"withdrawEnabled\":%s,\"collectionEnabled\":%s,"
                        + "\"transferEnabled\":%s}",
                command.walletEnabled(), command.scanEnabled(), command.withdrawEnabled(),
                command.collectionEnabled(), command.transferEnabled());
        audits.insert(UUID.randomUUID(), null, "PLATFORM_USER", actor.actorId().toString(),
                "WALLET_GLOBAL_SWITCHES.UPDATE", "WALLET_GLOBAL_SWITCHES", "global",
                sourceIp, details);
        return summary(actor);
    }

    private List<AnomalyView> findAnomalies(boolean keysetConfigured,
                                            boolean production,
                                            List<AccountChainProfile> profiles,
                                            List<TokenConfiguration> tokens,
                                            List<ChainAsset> assets,
                                            List<Overview> rpcNodes) {
        List<AnomalyView> anomalies = new ArrayList<>();
        if (!keysetConfigured) {
            anomalies.add(new AnomalyView(
                    "KEYSET_NOT_CONFIGURED", "ERROR", null, null,
                    "Wallet keyset is not configured."));
        }

        Map<String, List<AccountChainProfile>> enabledProfiles = profiles.stream()
                .filter(AccountChainProfile::getEnabled)
                .collect(Collectors.groupingBy(profile -> normalize(profile.getChain())));
        enabledProfiles.forEach((chain, rows) -> {
            if (rows.size() > 1) {
                anomalies.add(new AnomalyView(
                        "MULTIPLE_ENABLED_NETWORKS", "ERROR", chain, null,
                        "Only one network can be enabled per chain at a time: "
                                + rows.stream().map(AccountChainProfile::getNetwork).collect(Collectors.joining(", "))));
            }
        });

        Set<NetworkKey> enabledProfileKeys = profiles.stream()
                .filter(AccountChainProfile::getEnabled)
                .map(profile -> profileKey(profile.getChain(), profile.getNetwork()))
                .collect(Collectors.toSet());
        Set<NetworkKey> enabledRpcKeys = rpcNodes.stream()
                .filter(Overview::enabled)
                .filter(this::currentEnvironment)
                .map(rpc -> profileKey(rpc.chain(), rpc.network()))
                .collect(Collectors.toSet());
        for (AccountChainProfile profile : profiles) {
            if (!profile.getEnabled()) {
                continue;
            }
            if (production && !WalletEnvironmentPolicy.isProductionNetwork(profile.getNetwork())) {
                anomalies.add(new AnomalyView(
                        "NON_PRODUCTION_NETWORK", "ERROR", profile.getChain(), profile.getNetwork(),
                        "Production cannot enable a non-production network."));
            }
            if (!enabledRpcKeys.contains(profileKey(profile.getChain(), profile.getNetwork()))) {
                anomalies.add(new AnomalyView(
                        "RPC_NODE_MISSING", "ERROR", profile.getChain(), profile.getNetwork(),
                        "No enabled RPC node is configured for environment " + environment + "."));
            }
        }

        Map<AssetKey, ChainAsset> activeAssets = assets.stream()
                .filter(ChainAsset::getActive)
                .collect(Collectors.toMap(
                        asset -> assetKey(asset.getChain(), asset.getSymbol()),
                        asset -> asset,
                        (left, right) -> left));
        Set<AssetKey> enabledTokenAssets = new LinkedHashSet<>();
        for (TokenConfiguration token : tokens) {
            if (!token.enabled()) {
                continue;
            }
            AssetKey tokenAssetKey = assetKey(token.chain(), token.symbol());
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
            ChainAsset asset = activeAssets.get(tokenAssetKey);
            if (asset == null) {
                anomalies.add(new AnomalyView(
                        "TOKEN_ASSET_MISSING", "ERROR", token.chain(), token.network(),
                        token.symbol() + " has no matching active chain asset."));
            } else if (!sameContract(token.contractAddress(), asset.getContractAddress())) {
                anomalies.add(new AnomalyView(
                        "TOKEN_CONTRACT_MISMATCH", "ERROR", token.chain(), token.network(),
                        token.symbol() + " contract differs from chain_asset."));
            }
        }
        for (ChainAsset asset : activeAssets.values()) {
            if (!enabledTokenAssets.contains(assetKey(asset.getChain(), asset.getSymbol()))) {
                anomalies.add(new AnomalyView(
                        "ASSET_TOKEN_MISSING", "ERROR", asset.getChain(), null,
                        asset.getSymbol() + " has no matching enabled token configuration."));
            }
        }
        return List.copyOf(anomalies);
    }

    private ChainStatusView chainStatus(AccountChainProfile profile,
                                        GlobalSwitches global,
                                        boolean keysetConfigured,
                                        boolean production,
                                        long enabledNetworkCount,
                                        int tokenCount,
                                        int rpcNodeCount) {
        TaskSwitches configured = new TaskSwitches(
                profile.getScanEnabled(), profile.getWithdrawEnabled(),
                profile.getCollectionEnabled(), profile.getTransferEnabled());
        TaskSwitches effective = new TaskSwitches(
                global.effective(profile.getEnabled(), global.taskEnabled(TASK_SCAN, profile.getScanEnabled())),
                global.effective(profile.getEnabled(), global.taskEnabled(TASK_WITHDRAW, profile.getWithdrawEnabled())),
                global.effective(profile.getEnabled(), global.taskEnabled(TASK_COLLECTION, profile.getCollectionEnabled())),
                global.effective(profile.getEnabled(), global.taskEnabled(TASK_TRANSFER, profile.getTransferEnabled())));
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (!profile.getEnabled()) {
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
            if (production && !WalletEnvironmentPolicy.isProductionNetwork(profile.getNetwork())) {
                blockers.add("This network is not allowed in production.");
            }
            if (!global.walletEnabled()) {
                blockers.add("Wallet master switch is off.");
            }
            if (profile.getScanEnabled() && !global.scanEnabled()) {
                blockers.add("Global scan switch is off.");
            }
            if (profile.getWithdrawEnabled() && !global.withdrawEnabled()) {
                blockers.add("Global withdrawal switch is off.");
            }
            if (profile.getCollectionEnabled() && !global.collectionEnabled()) {
                blockers.add("Global collection switch is off.");
            }
            if (profile.getTransferEnabled() && !global.transferEnabled()) {
                blockers.add("Global transfer switch is off.");
            }
            if (!configured.anyEnabled()) {
                blockers.add("All chain task switches are off.");
            }
        }
        String status;
        if (!profile.getEnabled()) {
            status = "DISABLED";
        } else if (!blockers.isEmpty()) {
            status = "BLOCKED";
        } else if (!effective.anyEnabled()) {
            status = "INACTIVE";
        } else {
            status = "ACTIVE";
        }
        return new ChainStatusView(
                profile.getId(), profile.getChain(), profile.getNetwork(), profile.getFamily(),
                profile.getEnabled(), configured, effective, tokenCount, rpcNodeCount,
                status, List.copyOf(blockers));
    }
    private Map<NetworkKey, Integer> enabledTokenCounts(List<TokenConfiguration> tokens) {
        Map<NetworkKey, Integer> counts = new HashMap<>();
        tokens.stream().filter(TokenConfiguration::enabled).forEach(token ->
                counts.merge(profileKey(token.chain(), token.network()), 1, Integer::sum));
        return counts;
    }
    private Map<NetworkKey, Integer> enabledRpcCounts(List<Overview> nodes) {
        Map<NetworkKey, Integer> counts = new HashMap<>();
        nodes.stream().filter(Overview::enabled).filter(this::currentEnvironment).forEach(node ->
                counts.merge(profileKey(node.chain(), node.network()), 1, Integer::sum));
        return counts;
    }
    private boolean currentEnvironment(Overview row) {
        return environment.equalsIgnoreCase(row.environment());
    }

    private static boolean sameContract(String left, String right) {
        return left != null && right != null && !left.isBlank() && !right.isBlank()
                && left.trim().equalsIgnoreCase(right.trim());
    }
    private static NetworkKey profileKey(String chain, String network) {
        return new NetworkKey(normalize(chain), normalize(network));
    }
    private static AssetKey assetKey(String chain, String symbol) {
        return new AssetKey(normalize(chain), normalize(symbol));
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    private static String normalizeEnvironment(String value) {
        return value == null || value.isBlank() ? "dev" : value.trim();
    }

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

    private record NetworkKey(String chain, String network) { }
    private record AssetKey(String chain, String symbol) { }
}
