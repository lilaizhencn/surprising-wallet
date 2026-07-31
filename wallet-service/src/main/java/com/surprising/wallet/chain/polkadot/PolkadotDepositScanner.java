package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Polkadot 链充值扫描器，通过运行时服务扫描中继链和 Asset Hub 的转账事件。
 *
 * <p>分为两个独立的扫描管线：
 * <ul>
 *   <li>原生扫描器（polkadot-runtime-scanner）：扫描中继链 DOT 转账</li>
 *   <li>Asset Hub 扫描器（polkadot-assethub-scanner）：扫描 Asset Hub 上的资产转账</li>
 * </ul>
 * 每个扫描器使用独立的高度计数器，分别追踪各自的扫描进度。</p>
 *
 * @see PolkadotRuntimeClient
 */
@Service
@RequiredArgsConstructor
@Slf4j
public
class PolkadotDepositScanner {

    /** 链标识 */
    private static final String CHAIN = PolkadotRuntimeClient.CHAIN;

    /** 原生币符号 */
    private static final String SYMBOL = "DOT";

    /** 原生扫描器名称 */
    private static final String NATIVE_SCANNER = "polkadot-runtime-scanner";

    /** Asset Hub 扫描器名称 */
    private static final String ASSET_HUB_SCANNER = "polkadot-assethub-scanner";

    /** 充值钱包角色 */
    private static final String WALLET_ROLE_DEPOSIT = "DEPOSIT";

    /** 合约部署者钱包角色（也跟踪 DOT 充值） */
    private static final String WALLET_ROLE_CONTRACT_DEPLOYER = "CONTRACT_DEPLOYER";

    /** DOT 默认小数位数 */
    private static final int DEFAULT_DOT_DECIMALS = 10;

    /** 运行时客户端 */
    private final PolkadotRuntimeClient runtimeClient;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;
    /**
     * 扫描或观察 {@code scanAndCredit} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "polkadot scanAndCredit");
        AccountChainProfile profile = profile();
        List<DepositEvent> events = new ArrayList<>();
        events.addAll(scanNative(profile));
        Map<String, TokenDefinition> tokens = tokensByAssetId();
        if (!tokens.isEmpty()) {
            events.addAll(scanAssets(profile, tokens));
        }
        return events;
    }
    /**
     * 扫描或观察 {@code scanNative} 对应的链上状态，并转换为业务可用结果。
     */
    private List<DepositEvent> scanNative(AccountChainProfile profile) {
        long latest = runtimeClient.latestFinalizedHeight();
        int requiredConfirmations = requiredConfirmations(profile);
        long safeHeight = Math.max(0L, latest - requiredConfirmations + 1L);
        long start = scanStart(profile, safeHeight, NATIVE_SCANNER);
        if (start > safeHeight) {
            repository.updateScanHeight(CHAIN, NATIVE_SCANNER, latest, safeHeight);
            return List.of();
        }
        long end = Math.min(safeHeight, start + scanBatch(profile) - 1L);
        Map<String, ChainAddressRecord> addresses = trackedDepositAddresses(SYMBOL);
        List<String> originalAddresses = addresses.values().stream()
                .map(ChainAddressRecord::getAddress)
                .distinct()
                .toList();
        List<DepositEvent> events = new ArrayList<>();
        List<PolkadotRuntimeClient.TransferEvent> transfers = runtimeClient.scanNativeTransfers(
                start, end, originalAddresses);
        if (!transfers.isEmpty()) {
            log.info("polkadot native scan found transfers count={} range={}-{}", transfers.size(), start, end);
        }
        for (PolkadotRuntimeClient.TransferEvent transfer : transfers) {
            DepositEvent event = toNativeDepositEvent(transfer, addresses, latest);
            if (event == null) {
                log.warn("polkadot native scan ignored transfer txHash={} to={} amountPlanck={}",
                        transfer.txHash(), transfer.toAddress(), transfer.amountPlanck());
                continue;
            }
            ChainAddressRecord tracked = addresses.get(normalize(event.toAddress()));
            repository.recordAndCreditDeposit(event, transfer.eventIndex(), requiredConfirmations,
                    tracked.getAccountId());
            events.add(event);
        }
        repository.updateScanHeight(CHAIN, NATIVE_SCANNER, latest, end);
        return events;
    }
    /**
     * 扫描或观察 {@code scanAssets} 对应的链上状态，并转换为业务可用结果。
     */
    private List<DepositEvent> scanAssets(AccountChainProfile profile, Map<String, TokenDefinition> tokens) {
        long latest = runtimeClient.latestAssetHubFinalizedHeight();
        int requiredConfirmations = requiredConfirmations(profile);
        long safeHeight = Math.max(0L, latest - requiredConfirmations + 1L);
        long start = scanStart(profile, safeHeight, ASSET_HUB_SCANNER);
        if (start > safeHeight) {
            repository.updateScanHeight(CHAIN, ASSET_HUB_SCANNER, latest, safeHeight);
            return List.of();
        }
        long end = Math.min(safeHeight, start + scanBatch(profile) - 1L);
        Map<String, Map<String, ChainAddressRecord>> addressesBySymbol = trackedTokenDepositAddresses(tokens);
        List<String> addresses = addressesBySymbol.values().stream()
                .flatMap(addressBook -> addressBook.values().stream())
                .map(ChainAddressRecord::getAddress)
                .distinct()
                .toList();
        List<DepositEvent> events = new ArrayList<>();
        for (PolkadotRuntimeClient.TransferEvent transfer : runtimeClient.scanAssetTransfers(
                start, end, addresses, tokens)) {
            TokenDefinition token = tokens.get(PolkadotRuntimeClient.normalizeAssetId(transfer.assetId()));
            if (token == null) {
                continue;
            }
            Map<String, ChainAddressRecord> addressBook = addressesBySymbol.get(token.getSymbol());
            DepositEvent event = toAssetDepositEvent(transfer, addressBook, token, latest);
            if (event == null) {
                continue;
            }
            ChainAddressRecord tracked = addressBook.get(normalize(event.toAddress()));
            repository.recordAndCreditDeposit(event, transfer.eventIndex(), requiredConfirmations,
                    tracked.getAccountId());
            events.add(event);
        }
        repository.updateScanHeight(CHAIN, ASSET_HUB_SCANNER, latest, end);
        return events;
    }

    /**
     * 编码 {@code toNativeDepositEvent} 对应的数据，生成链上或接口所需的表示。
     */
    private DepositEvent toNativeDepositEvent(PolkadotRuntimeClient.TransferEvent transfer,
                                              Map<String, ChainAddressRecord> addresses,
                                              long latest) {
        ChainAddressRecord tracked = addresses.get(normalize(transfer.toAddress()));
        if (tracked == null || transfer.amountPlanck().signum() <= 0) {
            return null;
        }
        int confirmations = confirmations(latest, transfer.blockHeight());
        BigDecimal amount = fromAtomic(transfer.amountPlanck(), nativeDecimals());
        return new DepositEvent(ChainType.DOT, SYMBOL, transfer.txHash(),
                transfer.fromAddress(), tracked.getAddress(), amount, transfer.blockHeight(),
                transfer.txHash(), confirmations, null, transfer.rawPayload());
    }

    /**
     * 编码 {@code toAssetDepositEvent} 对应的数据，生成链上或接口所需的表示。
     */
    private DepositEvent toAssetDepositEvent(PolkadotRuntimeClient.TransferEvent transfer,
                                             Map<String, ChainAddressRecord> addresses,
                                             TokenDefinition token,
                                             long latest) {
        if (addresses == null) {
            return null;
        }
        ChainAddressRecord tracked = addresses.get(normalize(transfer.toAddress()));
        if (tracked == null || transfer.amountPlanck().signum() <= 0) {
            return null;
        }
        int confirmations = confirmations(latest, transfer.blockHeight());
        String assetId = PolkadotRuntimeClient.normalizeAssetId(transfer.assetId());
        BigDecimal amount = fromAtomic(transfer.amountPlanck(), token.getDecimals());
        return new DepositEvent(ChainType.DOT, token.getSymbol(), transfer.txHash(),
                transfer.fromAddress(), tracked.getAddress(), amount, transfer.blockHeight(),
                transfer.txHash(), confirmations, assetId, transfer.rawPayload());
    }
    /**
     * 执行 {@code trackedDepositAddresses} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Map<String, ChainAddressRecord> trackedDepositAddresses(String assetSymbol) {
        Map<String, ChainAddressRecord> addresses = new HashMap<>();
        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, assetSymbol)) {
            if (isTrackedRole(address)) {
                addresses.put(normalize(address.getAddress()), address);
            }
        }
        return addresses;
    }
    /**
     * 判断 {@code isTrackedRole} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private boolean isTrackedRole(ChainAddressRecord address) {
        if (address == null) {
            return false;
        }
        String role = address.getWalletRole();
        if (WALLET_ROLE_DEPOSIT.equals(role)) {
            return true;
        }
        return SYMBOL.equalsIgnoreCase(address.getAssetSymbol())
                && WALLET_ROLE_CONTRACT_DEPLOYER.equals(role);
    }

    /**
     * 执行 {@code trackedTokenDepositAddresses} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Map<String, Map<String, ChainAddressRecord>> trackedTokenDepositAddresses(
            Map<String, TokenDefinition> tokens) {
        Map<String, Map<String, ChainAddressRecord>> addressesBySymbol = new HashMap<>();
        for (TokenDefinition token : tokens.values()) {
            addressesBySymbol.put(token.getSymbol(), trackedDepositAddresses(token.getSymbol()));
        }
        return addressesBySymbol;
    }
    /**
     * 编码 {@code tokensByAssetId} 对应的数据，生成链上或接口所需的表示。
     */
    private Map<String, TokenDefinition> tokensByAssetId() {
        Map<String, TokenDefinition> tokens = new HashMap<>();
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            String assetId = PolkadotRuntimeClient.normalizeAssetId(token.getContractAddress());
            if (Boolean.TRUE.equals(token.getActive()) && !assetId.isBlank()) {
                tokens.put(assetId, token);
            }
        }
        return tokens;
    }
    /**
     * 扫描或观察 {@code scanStart} 对应的链上状态，并转换为业务可用结果。
     */
    private long scanStart(AccountChainProfile profile, long safeHeight, String scannerName) {
        return repository.findScanSafeHeight(CHAIN, scannerName)
                .map(height -> Math.min(height + 1L, safeHeight + 1L))
                .orElseGet(() -> {
                    Long configured = profile.getScanStartHeight();
                    if (configured != null && configured > 0) {
                        return Math.min(configured, safeHeight + 1L);
                    }
                    return Math.max(0L, safeHeight - scanBatch(profile) + 1L);
                });
    }
    /**
     * 扫描或观察 {@code scanBatch} 对应的链上状态，并转换为业务可用结果。
     */
    private static int scanBatch(AccountChainProfile profile) {
        Long maxBlocks = profile.getScanMaxBlocksPerRun();
        if (maxBlocks != null && maxBlocks > 0) {
            return Math.toIntExact(Math.min(maxBlocks, 100L));
        }
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 25 : Math.min(batchSize, 100);
    }
    /**
     * 校验 {@code requiredConfirmations} 对应的前置条件，不满足时抛出明确异常。
     */
    private static int requiredConfirmations(AccountChainProfile profile) {
        Integer configured = profile.getDepositConfirmations();
        return configured == null || configured <= 0 ? 12 : configured;
    }
    /**
     * 获取或查询 {@code profile} 对应的数据，并向调用方返回当前业务状态。
     */
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    /**
     * 执行 {@code nativeDecimals} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private int nativeDecimals() {
        return repository.findAsset(CHAIN, SYMBOL)
                .map(ChainAsset::getDecimals)
                .filter(decimals -> decimals != null && decimals > 0)
                .orElse(DEFAULT_DOT_DECIMALS);
    }
    /**
     * 处理 {@code confirmations} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private static int confirmations(long latest, long blockHeight) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, latest - blockHeight + 1L));
    }
    /**
     * 解析 {@code fromAtomic} 对应的输入，并转换为当前业务模型。
     */
    private static BigDecimal fromAtomic(BigInteger amount, int decimals) {
        return new BigDecimal(amount).movePointLeft(decimals).stripTrailingZeros();
    }
    /**
     * 转换或计算 {@code normalize} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalize(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }
    /**
     * 校验 {@code requireTaskEnabled} 对应的前置条件，不满足时抛出明确异常。
     */
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
}
