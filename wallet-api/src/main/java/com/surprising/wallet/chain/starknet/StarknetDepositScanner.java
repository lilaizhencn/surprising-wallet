package com.surprising.wallet.chain.starknet;

import com.swmansion.starknet.data.types.EmittedEvent;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.StarknetTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Starknet ERC-20 Transfer 充值扫描器，支持 STRK 原生资产和配置的 Starknet Token。
 */
@Service
@RequiredArgsConstructor
public class StarknetDepositScanner {
    /** 链标识。 */
    private static final String CHAIN = "STARKNET";
    /** 扫描器名称前缀。 */
    private static final String SCANNER_PREFIX = "starknet-transfer-";
    /** Starknet uint256 高低位分界。 */
    private static final int UINT128_BITS = 128;

    /** Starknet RPC 客户端。 */
    private final StarknetRpcClient rpc;
    /** 数据库仓储。 */
    private final ChainJdbcRepository repository;
    /** 运行时任务开关。 */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /** 执行一次完整的 Starknet 充值扫描并触发入账。 */
    public List<DepositEvent> scanAndCredit(AccountChainProfile profile) {
        requireTaskEnabled();
        if (!CHAIN.equalsIgnoreCase(profile.getChain())
                || !"starknet".equalsIgnoreCase(profile.getFamily())) {
            throw new IllegalArgumentException("Starknet profile is required");
        }
        StarknetRpcClient.BlockTip tip = rpc.latest(profile);
        long latest = tip.number();
        Map<String, ChainAddressRecord> addresses = repository.listChainAddresses(CHAIN)
                .stream()
                .filter(address -> "DEPOSIT".equalsIgnoreCase(address.getWalletRole()))
                .collect(Collectors.toMap(
                        address -> StarknetKeyService.normalizeAddress(address.getAddress()),
                        address -> address,
                        (left, right) -> left,
                        HashMap::new));
        if (addresses.isEmpty()) {
            repository.updateScanHeight(CHAIN, SCANNER_PREFIX + profile.getNativeSymbol(), latest,
                    safeHeight(profile, latest));
            return List.of();
        }

        List<AssetSpec> assets = assets(profile);
        java.util.ArrayList<DepositEvent> discovered = new java.util.ArrayList<>();
        for (AssetSpec asset : assets) {
            scanAsset(profile, asset, addresses, latest, discovered);
        }
        repository.observeCanonicalBlock(CHAIN, SCANNER_PREFIX + "tip", latest, tip.hash(), null);
        return discovered;
    }

    /** 扫描单个 STRK 或 Token 合约。 */
    private void scanAsset(AccountChainProfile profile, AssetSpec asset,
                           Map<String, ChainAddressRecord> addresses, long latest,
                           List<DepositEvent> discovered) {
        String scanner = SCANNER_PREFIX + asset.symbol();
        long configured = profile.getScanStartHeight() == null ? 0L : profile.getScanStartHeight();
        long fallback = configured > 0 ? configured : Math.max(0L, latest - scanBatch(profile) + 1L);
        long start = repository.findScanSafeHeight(CHAIN, scanner)
                .map(height -> height + 1L)
                .orElse(fallback);
        long end = Math.min(latest, start + scanBatch(profile) - 1L);
        while (start <= end) {
            List<EmittedEvent> events = rpc.transferEvents(profile, asset.contractAddress(), start, end);
            for (EmittedEvent event : events) {
                processEvent(profile, asset, addresses, latest, event, discovered);
            }
            start = end + 1L;
        }
        repository.updateScanHeight(CHAIN, scanner, latest, safeHeight(profile, latest));
    }

    /** 将链上事件解析为统一充值事件并执行幂等入账。 */
    private void processEvent(AccountChainProfile profile, AssetSpec asset,
                              Map<String, ChainAddressRecord> addresses, long latest,
                              EmittedEvent event, List<DepositEvent> discovered) {
        if (event.getBlockNumber() == null || event.getKeys().size() < 3 || event.getData().size() < 2) {
            return;
        }
        String from = normalizeFelt(event.getKeys().get(1));
        String to = normalizeFelt(event.getKeys().get(2));
        ChainAddressRecord tracked = addresses.get(to);
        if (tracked == null) {
            return;
        }
        BigInteger atomic = event.getData().get(0).getValue()
                .add(event.getData().get(1).getValue().shiftLeft(UINT128_BITS));
        if (atomic.signum() <= 0) {
            return;
        }
        int confirmations = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1L, latest - event.getBlockNumber() + 1L));
        BigDecimal amount = new BigDecimal(atomic).movePointLeft(asset.decimals());
        String txHash = event.getTransactionHash().hexString().toLowerCase(Locale.ROOT);
        String blockHash = event.getBlockHash() == null ? null : event.getBlockHash().hexString();
        if (blockHash == null || blockHash.isBlank()) {
            throw new IllegalStateException("Starknet Transfer event is missing block hash: " + txHash);
        }
        DepositEvent deposit = new DepositEvent(ChainType.STARKNET, asset.symbol(), txHash, from, to, amount,
                event.getBlockNumber(), blockHash, confirmations, asset.contractAddress(), String.valueOf(event));
        repository.recordStarknetTransaction(StarknetTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(asset.symbol())
                .contractAddress(asset.contractAddress())
                .amount(amount)
                .fee(BigDecimal.ZERO)
                .blockHeight(event.getBlockNumber().longValue())
                .confirmations(confirmations)
                .status(confirmations >= requiredConfirmations(profile) ? "CONFIRMED" : "CONFIRMING")
                .rawPayload(String.valueOf(event))
                .build());
        repository.observeCanonicalBlock(CHAIN, SCANNER_PREFIX + asset.symbol(),
                event.getBlockNumber(), blockHash, null);
        if (repository.recordAndCreditDeposit(deposit, event.getEventIndex(), requiredConfirmations(profile),
                tracked.getAccountId())) {
            discovered.add(deposit);
        }
    }

    /** 构造启用的原生资产和 Token 扫描项。 */
    private List<AssetSpec> assets(AccountChainProfile profile) {
        AssetSpec nativeAsset = repository.findAsset(CHAIN, profile.getNativeSymbol())
                .filter(asset -> Boolean.TRUE.equals(asset.getActive()))
                .filter(asset -> Boolean.TRUE.equals(asset.getNativeAsset()))
                .filter(asset -> asset.getContractAddress() != null && !asset.getContractAddress().isBlank())
                .map(asset -> new AssetSpec(asset.getSymbol(), asset.getContractAddress(),
                        asset.getDecimals() == null ? 18 : asset.getDecimals()))
                .orElseThrow(() -> new IllegalStateException("enabled Starknet native asset is required"));
        java.util.ArrayList<AssetSpec> result = new java.util.ArrayList<>();
        result.add(nativeAsset);
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            if (token.getContractAddress() == null || token.getContractAddress().isBlank()) {
                continue;
            }
            result.add(new AssetSpec(token.getSymbol(), token.getContractAddress(), token.getDecimals()));
        }
        return result;
    }

    /** 计算确认所需的安全高度。 */
    private long safeHeight(AccountChainProfile profile, long latest) {
        return Math.max(0L, latest - requiredConfirmations(profile) + 1L);
    }

    /** 返回每次扫描最多处理的区块数。 */
    private long scanBatch(AccountChainProfile profile) {
        if (profile.getScanMaxBlocksPerRun() != null && profile.getScanMaxBlocksPerRun() > 0) {
            return profile.getScanMaxBlocksPerRun();
        }
        if (profile.getScanBatchSize() != null && profile.getScanBatchSize() > 0) {
            return profile.getScanBatchSize();
        }
        return 20L;
    }

    /** 返回链配置的充值确认数。 */
    private int requiredConfirmations(AccountChainProfile profile) {
        return profile.getDepositConfirmations() == null
                ? 1 : Math.max(1, profile.getDepositConfirmations());
    }

    /** 标准化 felt 地址。 */
    private String normalizeFelt(com.swmansion.starknet.data.types.Felt value) {
        return StarknetKeyService.normalizeAddress(value.hexString());
    }

    /** 校验扫描任务开关。 */
    private void requireTaskEnabled() {
        if (runtimeConfigService != null
                && !runtimeConfigService.isTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_SCAN)) {
            throw new IllegalStateException("Starknet scan task is disabled");
        }
    }

    /** 单个 Starknet 资产扫描定义。 */
    private record AssetSpec(String symbol, String contractAddress, int decimals) {
    }
}
