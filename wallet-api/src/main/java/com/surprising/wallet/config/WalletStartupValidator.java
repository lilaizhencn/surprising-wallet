package com.surprising.wallet.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 应用启动自检器，防止在链配置、RPC 配置、资产配置存在缺失时上线路径运行。
 */
public class WalletStartupValidator implements ApplicationRunner {
    /** 链配置仓储，用于读取 chain_profile/rpc/config 运行状态。 */
    private final ChainJdbcRepository repository;
    /** 运行时开关配置，核验任务总开关与链级开关。 */
    private final WalletRuntimeConfigService runtimeConfigService;
    /** 原始 SQL 查询器，用于关键配置一致性校验。 */
    private final JdbcTemplate jdbcTemplate;
    /** 钱包密钥提供者，用于启动前校验签名材料是否已配置。 */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 当前环境标识（dev/test/prod）。 */
    @Value("${sw.app.env.name:dev}")
    private String environmentName;

    /**
     * 启动顺序执行三类校验：密钥、资产/代币配置、链与 RPC 配置；
     * 任何不一致直接抛异常阻断应用启动。
     */
    @Override
    public void run(ApplicationArguments args) {
        boolean keysetConfigured = validateKeyset();
        validateEnabledAssetsAndTokens();
        validateProfiles();
        if (!keysetConfigured) {
            log.warn("wallet keyset is not configured; address derivation and signing are unavailable");
        }
        logRuntimeMatrix();
    }

    /**
     * 校验签名公钥材料是否都已加载，确保地址派生与签名服务可用。
     */
    private boolean validateKeyset() {
        if (!keyMaterial.isConfigured()) {
            return false;
        }
        keyMaterial.sig1PublicRoot();
        keyMaterial.sig2PublicRoot();
        keyMaterial.recoveryPublicRoot();
        keyMaterial.ed25519();
        log.info("wallet keyset check passed: four seeds loaded from wallet_key_config");
        return true;
    }

    /**
     * 校验启用资产与代币配置，确认合约地址非占位符且 token 与 chain_asset 一一对应。
     */
    void validateEnabledAssetsAndTokens() {
        List<Map<String, Object>> tokenRows = jdbcTemplate.queryForList("""
                select chain, symbol,
                       network,
                       coalesce(contract_address, contract_address_base58, contract_address_hex) as contract_address
                  from token_config
                 where enabled = true
                """);
        Map<String, Set<String>> enabledProfileNetworks = jdbcTemplate.queryForList("""
                select chain, network
                  from chain_profile
                 where enabled = true
                """).stream()
                .collect(Collectors.groupingBy(
                        row -> normalized(String.valueOf(row.get("chain"))),
                        Collectors.mapping(
                                row -> normalized(stringValue(row.get("network"))),
                                Collectors.toSet())));
        Map<String, String> tokenContracts = tokenRows.stream()
                .collect(Collectors.toMap(
                        row -> assetKey(String.valueOf(row.get("chain")), String.valueOf(row.get("symbol"))),
                        row -> stringValue(row.get("contract_address")),
                        (left, right) -> left));
        for (Map<String, Object> row : tokenRows) {
            String chain = String.valueOf(row.get("chain"));
            String symbol = String.valueOf(row.get("symbol"));
            String contract = stringValue(row.get("contract_address"));
            if (!StringUtils.hasText(contract) || containsPlaceholder(contract)) {
                throw new IllegalStateException(
                        "enabled token_config requires real contract address: "
                                + chain + "/" + symbol);
            }
            String tokenNetwork = stringValue(row.get("network"));
            if (StringUtils.hasText(tokenNetwork)) {
                Set<String> profileNetworks = enabledProfileNetworks.get(normalized(chain));
                if (profileNetworks == null || !profileNetworks.contains(normalized(tokenNetwork))) {
                    throw new IllegalStateException(
                            "enabled token_config network must match enabled chain_profile: "
                                    + chain + "/" + symbol
                                    + " tokenNetwork=" + tokenNetwork
                                    + " enabledProfileNetworks=" + profileNetworks);
                }
            }
        }

        List<Map<String, Object>> assetRows = jdbcTemplate.queryForList("""
                select chain, symbol, contract_address
                  from chain_asset
                 where active = true and native_asset = false
                """);
        for (Map<String, Object> row : assetRows) {
            String chain = String.valueOf(row.get("chain"));
            String symbol = String.valueOf(row.get("symbol"));
            String contract = stringValue(row.get("contract_address"));
            if (!StringUtils.hasText(contract) || containsPlaceholder(contract)) {
                throw new IllegalStateException(
                        "active token chain_asset requires real contract address: "
                                + chain + "/" + symbol);
            }
            String tokenContract = tokenContracts.get(assetKey(chain, symbol));
            if (!StringUtils.hasText(tokenContract)) {
                throw new IllegalStateException(
                        "active token chain_asset requires enabled token_config: "
                                + chain + "/" + symbol);
            }
            if (!sameContract(contract, tokenContract)) {
                throw new IllegalStateException(
                        "active token chain_asset contract must match enabled token_config: "
                                + chain + "/" + symbol);
                }
        }
    }

    /**
     * 校验所有已启用链配置：
     * 每条链必须有且仅有一个启用网络；生产环境只允许生产网络。
     */
    List<AccountChainProfile> validateProfiles() {
        List<AccountChainProfile> enabledProfiles = repository.listEnabledChainProfiles();
        if (enabledProfiles.isEmpty()) {
            throw new IllegalStateException("no enabled chain_profile rows");
        }

        Map<String, List<AccountChainProfile>> byChain = enabledProfiles.stream()
                .collect(Collectors.groupingBy(profile -> profile.getChain().toUpperCase(Locale.ROOT)));
        byChain.forEach((chain, profiles) -> {
            if (profiles.size() > 1) {
                String networks = profiles.stream()
                        .map(AccountChainProfile::getNetwork)
                        .collect(Collectors.joining(","));
                throw new IllegalStateException(
                        "chain_profile has multiple enabled networks for " + chain + ": " + networks);
            }
        });

        boolean prod = WalletEnvironmentPolicy.isProduction(environmentName);
        for (AccountChainProfile profile : enabledProfiles) {
            if (prod && !WalletEnvironmentPolicy.isProductionNetwork(profile.getNetwork())) {
                throw new IllegalStateException(
                        "production environment cannot enable test network profile: "
                                + profile.getChain() + "/" + profile.getNetwork());
            }
            validateRequiredRpcPurposes(profile);
        }
        return enabledProfiles;
    }

    /**
     * 校验单个 RPC 节点字段完整性与认证信息缺失问题。
     */
    private void validateRpcNode(AccountChainProfile profile, ChainRpcNode node) {
        if (!StringUtils.hasText(node.getRpcUrl())) {
            throw new IllegalStateException(
                    "enabled chain_rpc_node has empty rpc_url: "
                            + profile.getChain() + "/" + profile.getNetwork()
                            + " env=" + environmentName
                            + " label=" + node.getNodeLabel());
        }
        if (containsPlaceholder(node.getRpcUrl())
                || containsPlaceholder(node.getApiKey())
                || containsPlaceholder(node.getUsername())
                || containsPlaceholder(node.getPassword())) {
            throw new IllegalStateException(
                    "enabled chain_rpc_node still contains placeholder credentials or URL: "
                            + profile.getChain() + "/" + profile.getNetwork()
                    + " env=" + environmentName
                    + " label=" + node.getNodeLabel());
        }
        validateRpcNodeCredentials(profile, node);
    }

    /**
     * 按连接类型校验 RPC 认证策略：API key 或用户口令必须齐备。
     */
    private void validateRpcNodeCredentials(AccountChainProfile profile, ChainRpcNode node) {
        String authType = normalized(node.getAuthType());
        String connectionType = normalized(node.getConnectionType());
        if (WalletRpcPolicy.requiresApiKey(authType, connectionType) && !StringUtils.hasText(node.getApiKey())) {
            throw new IllegalStateException(
                    "enabled chain_rpc_node requires api_key: "
                            + profile.getChain() + "/" + profile.getNetwork()
                            + " env=" + environmentName
                            + " label=" + node.getNodeLabel());
        }
        if (WalletRpcPolicy.requiresUsernamePassword(authType)
                && (!StringUtils.hasText(node.getUsername()) || !StringUtils.hasText(node.getPassword()))) {
            throw new IllegalStateException(
                    "enabled chain_rpc_node requires username/password: "
                            + profile.getChain() + "/" + profile.getNetwork()
                            + " env=" + environmentName
                            + " label=" + node.getNodeLabel());
        }
    }

    /**
     * 校验每个链 profile 对应环境开关下，所需 purpose 的 RPC 都有可用节点。
     */
    private void validateRequiredRpcPurposes(AccountChainProfile profile) {
        for (String purpose : WalletRpcPolicy.requiredPurposes(
                profile.getChain(), profile.getNetwork(), !repository.listTokens(profile.getChain()).isEmpty())) {
            List<ChainRpcNode> purposeNodes = repository.listEnabledRpcNodes(
                    profile.getChain(), profile.getNetwork(), environmentName, purpose);
            if (purposeNodes.isEmpty()) {
                throw new IllegalStateException(
                        "enabled chain_profile has no enabled chain_rpc_node for required purpose="
                                + purpose + ": "
                                + profile.getChain() + "/" + profile.getNetwork()
                                + " env=" + environmentName);
            }
            purposeNodes.forEach(node -> validateRpcNode(profile, node));
        }
    }

    /**
     * 判断值是否是占位符（例如 ${...} 或空白字符串）。
     */
    static boolean containsPlaceholder(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return WalletRpcPolicy.containsPlaceholder(value);
    }

    /**
     * 标准化链名/网络名用于一致性比较。
     */
    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 安全转字符串，null 输出空串，避免 JDBC 空值比较。
     */
    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 生成 chain/symbol 的统一键用于映射校验。
     */
    private static String assetKey(String chain, String symbol) {
        return normalized(chain) + "/" + normalized(symbol);
    }

    /**
     * 忽略大小写比较合约地址一致性。
     */
    private static boolean sameContract(String left, String right) {
        return StringUtils.hasText(left)
                && StringUtils.hasText(right)
                && left.trim().equalsIgnoreCase(right.trim());
    }

    /**
     * 打印当前运行时环境与链配置矩阵，便于启动日志审计留痕。
     */
    private void logRuntimeMatrix() {
        log.info("wallet runtime config env={} global all={} scan={} withdraw={} collection={} transfer={}",
                environmentName,
                repository.systemBoolean("global.all.enabled", true),
                runtimeConfigService.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_SCAN),
                runtimeConfigService.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW),
                runtimeConfigService.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION),
                runtimeConfigService.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_TRANSFER));

        for (AccountChainProfile profile : repository.listAllChainProfiles()) {
            List<ChainRpcNode> nodes = Boolean.TRUE.equals(profile.getEnabled())
                    ? repository.listAllEnabledRpcNodes(profile.getChain(), profile.getNetwork(), environmentName)
                    : List.of();
            if (!Boolean.TRUE.equals(profile.getEnabled())) {
                log.warn("chain disabled: {}/{}", profile.getChain(), profile.getNetwork());
                continue;
            }
            log.warn("chain status: chain={} network={} family={} scan={} withdraw={} collection={} transfer={} rpcNodes={} startHeight={} maxBlocks={}",
                    profile.getChain(),
                    profile.getNetwork(),
                    profile.getFamily(),
                    profile.getScanEnabled(),
                    profile.getWithdrawEnabled(),
                    profile.getCollectionEnabled(),
                    profile.getTransferEnabled(),
                    nodes.size(),
                    profile.getScanStartHeight(),
                    profile.getScanMaxBlocksPerRun());
        }
    }
}
