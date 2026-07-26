package com.surprising.wallet.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * 钱包运行时配置服务，实时读取系统开关及链级别的任务开关状态。
 *
 * <p>任务类型包括扫描（scan）、提现（withdraw）、归集（collection）、转账（transfer）。
 * 开关优先级：全局总开关 > 全局任务开关 > 链级别任务开关。任何一层关闭即视为禁用。</p>
 *
 * @see ChainJdbcRepository
 * @see AssetRuntimeMetadata
 */
@Service
@RequiredArgsConstructor
public class WalletRuntimeConfigService {
    /** 扫描任务标识 */
    public static final String TASK_SCAN = "scan";
    /** 提现任务标识 */
    public static final String TASK_WITHDRAW = "withdraw";
    /** 归集任务标识 */
    public static final String TASK_COLLECTION = "collection";
    /** 转账任务标识 */
    public static final String TASK_TRANSFER = "transfer";
    /** 提现管理员审批开关键 */
    public static final String WITHDRAWAL_ADMIN_APPROVAL_REQUIRED = "withdrawal.admin.approval.required";

    /**
     * 按链平均出块速度设置的扫描间隔。调度器只负责高频检查，到期后才访问链 RPC。
     * 未列出的新链使用 10 秒保守默认值，避免新增链因遗漏配置而完全不扫描。
     */
    private static final Map<String, Long> SCAN_INTERVAL_MILLIS = Map.ofEntries(
            Map.entry("SOLANA", 2_000L),
            Map.entry("APTOS", 2_000L),
            Map.entry("SUI", 2_000L),
            Map.entry("MONAD", 1_000L),
            Map.entry("INK", 1_000L),
            Map.entry("TAIKO", 2_000L),
            Map.entry("SONEIUM", 2_000L),
            Map.entry("MODE", 2_000L),
            Map.entry("LISK", 2_000L),
            Map.entry("KATANA", 1_000L),
            Map.entry("MEGAETH", 1_000L),
            Map.entry("X_LAYER", 1_000L),
            Map.entry("DEGEN", 2_000L),
            Map.entry("ROBINHOOD_CHAIN", 1_000L),
            Map.entry("ETHERLINK", 1_000L),
            Map.entry("IOTA_EVM", 15_000L),
            Map.entry("OASIS_EMERALD", 5_000L),
            Map.entry("CRONOS", 1_000L),
            Map.entry("SONIC", 1_000L),
            Map.entry("PULSECHAIN", 10_000L),
            Map.entry("ZETACHAIN", 4_000L),
            Map.entry("CORE", 3_000L),
            Map.entry("SOMNIA", 1_000L),
            Map.entry("NEAR", 2_000L),
            Map.entry("HYPERCORE", 2_000L),
            Map.entry("ARBITRUM", 2_000L),
            Map.entry("OPTIMISM", 2_000L),
            Map.entry("BASE", 2_000L),
            Map.entry("WORLD_CHAIN", 2_000L),
            Map.entry("AVAX", 2_000L),
            Map.entry("FANTOM", 2_000L),
            Map.entry("ZKSYNC", 2_000L),
            Map.entry("MANTLE", 2_000L),
            Map.entry("UNICHAIN", 2_000L),
            Map.entry("BERACHAIN", 2_000L),
            Map.entry("TRON", 3_000L),
            Map.entry("BSC", 3_000L),
            Map.entry("POLYGON", 3_000L),
            Map.entry("LINEA", 3_000L),
            Map.entry("SCROLL", 3_000L),
            Map.entry("XRP", 4_000L),
            Map.entry("TON", 5_000L),
            Map.entry("CELO", 5_000L),
            Map.entry("GNOSIS", 5_000L),
            Map.entry("DOT", 6_000L),
            Map.entry("XMR", 10_000L),
            Map.entry("ETH", 12_000L),
            Map.entry("DOGE", 15_000L),
            Map.entry("ADA", 20_000L),
            Map.entry("LTC", 30_000L),
            Map.entry("BTC", 60_000L),
            Map.entry("BCH", 60_000L));
    private static final long DEFAULT_SCAN_INTERVAL_MILLIS = 10_000L;

    private final ChainJdbcRepository repository;

    /**
     * 查询指定资产的某任务是否启用。
     *
     * @param asset 资产运行时元数据
     * @param task  任务类型（scan / withdraw / collection / transfer）
     * @return 是否启用
     */
    public boolean isTaskEnabled(AssetRuntimeMetadata asset, String task) {
        return asset != null && isTaskEnabled(asset.chain(), task);
    }

    /**
     * 查询指定链的某任务是否启用，含全局开关校验。
     *
     * @param chain 链名称
     * @param task  任务类型
     * @return 是否启用
     */
    public boolean isTaskEnabled(String chain, String task) {
        if (!repository.systemBoolean("global.all.enabled", true)) {
            return false;
        }
        if (!repository.systemBoolean("global." + task + ".enabled", true)) {
            return false;
        }
        return repository.findProfileByChain(chain)
                .map(profile -> chainTaskEnabled(profile, task))
                .orElse(false);
    }
    /**
     * 查询全局级别某任务是否启用（不区分链）。
     *
     * @param task 任务类型
     * @return 是否启用
     */
    public boolean isGlobalTaskEnabled(String task) {
        return repository.systemBoolean("global.all.enabled", true)
                && repository.systemBoolean("global." + task + ".enabled", true);
    }
    /** @return 全局总开关是否启用 */
    public boolean isGlobalEnabled() {
        return repository.systemBoolean("global.all.enabled", true);
    }

    /**
     * 返回指定链的扫描间隔。间隔集中维护，Account-Chain 与 UTXO 调度共享同一策略。
     */
    public long scanIntervalMillis(String chain) {
        if (chain == null || chain.isBlank()) {
            return DEFAULT_SCAN_INTERVAL_MILLIS;
        }
        return SCAN_INTERVAL_MILLIS.getOrDefault(
                chain.trim().toUpperCase(Locale.ROOT), DEFAULT_SCAN_INTERVAL_MILLIS);
    }

    /** @return 提现是否需要管理员审批 */
    public boolean isWithdrawalAdminApprovalRequired() {
        return repository.systemBoolean(WITHDRAWAL_ADMIN_APPROVAL_REQUIRED, false);
    }

    /**
     * 要求指定链的某任务已启用，否则抛出 {@link IllegalStateException}。
     *
     * @param chain     链名称
     * @param task      任务类型
     * @param operation 操作描述（用于异常消息）
     * @throws IllegalStateException 如果任务未启用
     */
    public void requireTaskEnabled(String chain, String task, String operation) {
        if (isTaskEnabled(chain, task)) {
            return;
        }
        throw new IllegalStateException("wallet runtime switch disabled: chain="
                + chain + " task=" + task + " operation=" + operation);
    }
    /**
     * 获取扫描起始高度。
     *
     * @param asset 资产运行时元数据
     * @return 起始高度，若未配置则返回 0
     */
    public long scanStartHeight(AssetRuntimeMetadata asset) {
        return scanStartHeight(asset.chain());
    }

    /**
     * 获取扫描起始高度。
     *
     * @param chain 链名称
     * @return 起始高度，若未配置则返回 0
     */
    public long scanStartHeight(String chain) {
        return repository.findProfileByChain(chain)
                .map(AccountChainProfile::getScanStartHeight)
                .filter(value -> value > 0)
                .orElse(0L);
    }
    /**
     * 获取每次扫描的最大区块数。
     *
     * @param asset 资产运行时元数据
     * @return 最大区块数，若未配置则返回 0
     */
    public long scanMaxBlocksPerRun(AssetRuntimeMetadata asset) {
        return scanMaxBlocksPerRun(asset.chain());
    }

    /**
     * 获取每次扫描的最大区块数。
     *
     * @param chain 链名称
     * @return 最大区块数，若未配置则返回 0
     */
    public long scanMaxBlocksPerRun(String chain) {
        return repository.findProfileByChain(chain)
                .map(AccountChainProfile::getScanMaxBlocksPerRun)
                .filter(value -> value > 0)
                .orElse(0L);
    }
    /**
     * 根据链配置判断具体任务是否启用。
     *
     * @param profile 链配置
     * @param task    任务类型
     * @return 是否启用
     */
    private boolean chainTaskEnabled(AccountChainProfile profile, String task) {
        return switch (task) {
            case TASK_SCAN -> Boolean.TRUE.equals(profile.getScanEnabled());
            case TASK_WITHDRAW -> Boolean.TRUE.equals(profile.getWithdrawEnabled());
            case TASK_COLLECTION -> Boolean.TRUE.equals(profile.getCollectionEnabled());
            case TASK_TRANSFER -> Boolean.TRUE.equals(profile.getTransferEnabled());
            default -> false;
        };
    }
}
