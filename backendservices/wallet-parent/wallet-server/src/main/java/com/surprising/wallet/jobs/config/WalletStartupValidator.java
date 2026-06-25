package com.surprising.wallet.jobs.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.WalletPublicKey;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalletStartupValidator implements ApplicationRunner {
    private static final Set<String> MAIN_NETWORKS = Set.of("main", "mainnet", "mainnet-beta");

    private final ChainJdbcRepository repository;
    private final WalletRuntimeConfigService runtimeConfigService;
    private final HotWalletAddressService hotWalletAddressService;

    @Value("${sw.app.env.name:dev}")
    private String environmentName;

    @Override
    public void run(ApplicationArguments args) {
        validatePublicKeys();
        List<AccountChainProfile> enabledProfiles = validateProfiles();
        validateDefaultHotWallets(enabledProfiles);
        logRuntimeMatrix();
    }

    private void validatePublicKeys() {
        Map<Integer, WalletPublicKey> keys = repository.listEnabledWalletPublicKeys().stream()
                .collect(Collectors.toMap(WalletPublicKey::getKeySlot, Function.identity(), (left, right) -> left));
        for (int slot = 1; slot <= 3; slot++) {
            WalletPublicKey key = keys.get(slot);
            if (key == null || !StringUtils.hasText(key.getPublicKey())) {
                throw new IllegalStateException("wallet_public_key slot " + slot + " is required");
            }
        }
        log.info("wallet public key check passed: enabled slots={}", keys.keySet());
    }

    private List<AccountChainProfile> validateProfiles() {
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

        boolean prod = "prod".equalsIgnoreCase(environmentName)
                || "production".equalsIgnoreCase(environmentName);
        for (AccountChainProfile profile : enabledProfiles) {
            if (prod && !MAIN_NETWORKS.contains(profile.getNetwork().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "production environment cannot enable test network profile: "
                                + profile.getChain() + "/" + profile.getNetwork());
            }
            List<ChainRpcNode> nodes = repository.listEnabledRpcNodes(
                    profile.getChain(), profile.getNetwork(), environmentName);
            if (nodes.isEmpty()) {
                throw new IllegalStateException(
                        "enabled chain_profile has no enabled chain_rpc_node: "
                                + profile.getChain() + "/" + profile.getNetwork()
                                + " env=" + environmentName);
            }
        }
        return enabledProfiles;
    }

    private void validateDefaultHotWallets(List<AccountChainProfile> enabledProfiles) {
        for (AccountChainProfile profile : enabledProfiles) {
            ChainAddressRecord hotAddress = hotWalletAddressService.requireVerifiedDefaultHotAddress(profile);
            log.info("default hot wallet check passed: chain={} asset={} userId=0 biz=0 index=0 address={}",
                    profile.getChain(), profile.getNativeSymbol(), hotAddress.getAddress());
        }
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
                    ? repository.listEnabledRpcNodes(profile.getChain(), profile.getNetwork(), environmentName)
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
