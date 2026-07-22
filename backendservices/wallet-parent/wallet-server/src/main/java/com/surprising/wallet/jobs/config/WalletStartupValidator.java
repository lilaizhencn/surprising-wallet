package com.surprising.wallet.jobs.config;

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
public class WalletStartupValidator implements ApplicationRunner {
    private final ChainJdbcRepository repository;
    private final WalletRuntimeConfigService runtimeConfigService;
    private final JdbcTemplate jdbcTemplate;
    private final WalletKeyMaterialProvider keyMaterial;

    @Value("${sw.app.env.name:dev}")
    private String environmentName;

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

    static boolean containsPlaceholder(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return WalletRpcPolicy.containsPlaceholder(value);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String assetKey(String chain, String symbol) {
        return normalized(chain) + "/" + normalized(symbol);
    }

    private static boolean sameContract(String left, String right) {
        return StringUtils.hasText(left)
                && StringUtils.hasText(right)
                && left.trim().equalsIgnoreCase(right.trim());
    }

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
