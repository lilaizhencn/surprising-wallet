package com.surprising.wallet.service;

import com.surprising.wallet.chain.aptos.AptosDepositScanner;
import com.surprising.wallet.chain.cardano.CardanoDepositScanner;
import com.surprising.wallet.chain.evm.EvmDepositScanner;
import com.surprising.wallet.chain.hypercore.HyperCoreDepositScanner;
import com.surprising.wallet.chain.monero.MoneroDepositScanner;
import com.surprising.wallet.chain.near.NearDepositScanner;
import com.surprising.wallet.chain.polkadot.PolkadotDepositScanner;
import com.surprising.wallet.chain.solana.SolanaDepositScanner;
import com.surprising.wallet.chain.sui.SuiDepositScanner;
import com.surprising.wallet.chain.ton.TonDepositScanner;
import com.surprising.wallet.chain.tron.TronAddressCodec;
import com.surprising.wallet.chain.tron.TronClientFactory;
import com.surprising.wallet.chain.tron.TronDepositScanner;
import com.surprising.wallet.chain.tron.TronScanner;
import com.surprising.wallet.chain.tron.TronTridentClient;
import com.surprising.wallet.chain.xrp.XrpDepositScanner;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 账户链充值扫描工作流。
 *
 * <p>只负责选择对应链扫描器、计算扫描区间并执行扫描；提现、归集和确认由
 * {@link AccountChainWorkflowService} 编排。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountChainDepositWorkflow {
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code runtimeConfigService}，用于保存运行配置和策略参数。
     */
    private final WalletRuntimeConfigService runtimeConfigService;
    /**
     * 保存 {@code evmDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final EvmDepositScanner evmDepositScanner;
    /**
     * 保存 {@code hyperCoreDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final HyperCoreDepositScanner hyperCoreDepositScanner;
    /**
     * 保存 {@code solanaDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final SolanaDepositScanner solanaDepositScanner;
    /**
     * 保存 {@code aptosDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final AptosDepositScanner aptosDepositScanner;
    /**
     * 保存 {@code suiDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final SuiDepositScanner suiDepositScanner;
    /**
     * 保存 {@code tonDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final TonDepositScanner tonDepositScanner;
    /**
     * 保存 {@code xrpDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final XrpDepositScanner xrpDepositScanner;
    /**
     * 保存 {@code cardanoDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final CardanoDepositScanner cardanoDepositScanner;
    /**
     * 保存 {@code moneroDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final MoneroDepositScanner moneroDepositScanner;
    /**
     * 保存 {@code nearDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final NearDepositScanner nearDepositScanner;
    /**
     * 保存 {@code polkadotDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final PolkadotDepositScanner polkadotDepositScanner;
    /**
     * 保存 {@code tronClientFactory}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final TronClientFactory tronClientFactory;
    /**
     * 保存 {@code tronDepositScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final TronDepositScanner tronDepositScanner;

    /**
     * 扫描单条已启用账户链；链级开关关闭时不执行。
     */
    public void scan(AccountChainProfile profile) {
        if (!runtimeConfigService.isTaskEnabled(
                profile.getChain(), WalletRuntimeConfigService.TASK_SCAN)) {
            return;
        }
        try {
            switch (profile.getChain()) {
                case "SOLANA" -> solanaDepositScanner.scanAndCredit();
                case "APTOS" -> aptosDepositScanner.scanAndCredit();
                case "SUI" -> suiDepositScanner.scanAndCredit();
                case "TON" -> tonDepositScanner.scanAndCredit();
                case "XRP" -> xrpDepositScanner.scanAndCredit();
                case "ADA" -> cardanoDepositScanner.scanAndCredit();
                case "DOT" -> polkadotDepositScanner.scanAndCredit();
                case "NEAR" -> nearDepositScanner.scanAndCredit();
                case "XMR" -> moneroDepositScanner.scanAndCredit(profile);
                case "HYPERCORE" -> hyperCoreDepositScanner.scanAndCredit(profile);
                case "TRON" -> scanTron(profile);
                default -> {
                    if ("evm".equalsIgnoreCase(profile.getFamily())) {
                        scanEvm(profile);
                    }
                }
            }
        } catch (Exception error) {
            log.warn("account-chain deposit scan failed: chain={} error={}",
                    profile.getChain(), error.getMessage(), error);
        }
    }

    /**
     * 扫描或观察 {@code scanEvm} 对应的链上状态，并转换为业务可用结果。
     */
    private void scanEvm(AccountChainProfile profile) throws Exception {
        ChainType chainType = ChainType.valueOf(profile.getChain());
        long latest = evmDepositScanner.getLatestBlockNumber(chainType).longValueExact();
        evmDepositScanner.reconcileCreditedDeposits(chainType, latest);
        long start = scanStart(profile, latest, "native-evm", "erc20-evm");
        long end = Math.min(latest, start + scanBatch(profile) - 1L);
        for (long height = start; height <= end; height++) {
            evmDepositScanner.scanAndCreditNative(chainType, height);
            evmDepositScanner.scanAndCreditErc20(chainType, height);
        }
    }

    /**
     * 扫描或观察 {@code scanTron} 对应的链上状态，并转换为业务可用结果。
     */
    private void scanTron(AccountChainProfile profile) throws Exception {
        try (TronTridentClient client = tronClientFactory.create()) {
            long latest = client.getNowBlock().getBlockHeader().getRawData().getNumber();
            long start = scanStart(profile, latest, "TRON_TRX", "TRON_TRC20");
            long end = Math.min(latest, start + scanBatch(profile) - 1L);
            Set<String> addresses = repository.listEnabledChainScanAddresses("TRON");
            Map<String, TronScanner.TokenConfig> tokens = tronTokens();
            for (long height = start; height <= end; height++) {
                tronDepositScanner.scanAndCreditTrx(
                        client, height, addresses, profile.getDepositConfirmations());
                tronDepositScanner.scanAndCreditTrc20(
                        client, height, tokens, addresses, profile.getDepositConfirmations());
            }
        }
    }

    /**
     * 扫描或观察 {@code scanStart} 对应的链上状态，并转换为业务可用结果。
     */
    private long scanStart(AccountChainProfile profile, long latest, String... scannerNames) {
        long configured = profile.getScanStartHeight() == null ? 0L : profile.getScanStartHeight();
        long fallback = configured > 0
                ? configured
                : Math.max(0L, latest - scanBatch(profile) + 1L);
        long next = Long.MAX_VALUE;
        for (String scannerName : scannerNames) {
            long candidate = repository.findScanSafeHeight(profile.getChain(), scannerName)
                    .map(height -> height + 1L)
                    .orElse(fallback);
            next = Math.min(next, candidate);
        }
        return Math.min(next == Long.MAX_VALUE ? fallback : next, latest);
    }

    /**
     * 扫描或观察 {@code scanBatch} 对应的链上状态，并转换为业务可用结果。
     */
    private long scanBatch(AccountChainProfile profile) {
        long requiredConfirmations = profile.getDepositConfirmations() == null
                ? 1L
                : Math.max(1L, profile.getDepositConfirmations());
        if (profile.getScanMaxBlocksPerRun() != null
                && profile.getScanMaxBlocksPerRun() > 0) {
            return Math.max(requiredConfirmations, profile.getScanMaxBlocksPerRun());
        }
        if (profile.getScanBatchSize() != null && profile.getScanBatchSize() > 0) {
            return Math.max(requiredConfirmations, profile.getScanBatchSize());
        }
        return Math.max(requiredConfirmations, 20L);
    }

    /**
     * 执行 {@code tronTokens} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Map<String, TronScanner.TokenConfig> tronTokens() {
        Map<String, TronScanner.TokenConfig> tokens = new LinkedHashMap<>();
        for (TokenDefinition token : repository.listTokens("TRON")) {
            String contract = token.getContractAddress();
            if (contract == null || contract.isBlank()) {
                continue;
            }
            String hex = contract.startsWith("T")
                    ? TronAddressCodec.base58ToHex(contract)
                    : TronAddressCodec.normalizeHexAddress(contract);
            tokens.put(hex.toLowerCase(Locale.ROOT),
                    new TronScanner.TokenConfig(
                            token.getSymbol(), hex, token.getDecimals()));
        }
        return tokens;
    }
}
