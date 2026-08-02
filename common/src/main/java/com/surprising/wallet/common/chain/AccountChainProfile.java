package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 账户链配置档案，描述特定区块链网络的运行参数，包括 RPC 连接、费率、确认数等配置。
 *
 * <p>主要配置项：</p>
 * <ul>
 *   <li>{@code chain} / {@code network} / {@code family} - 链标识、网络环境和链家族</li>
 *   <li>{@code rpcUrl} - JSON-RPC 节点地址</li>
 *   <li>{@code explorerUrl} - 区块链浏览器地址</li>
 *   <li>{@code depositConfirmations} / {@code withdrawConfirmations} - 入金/出金确认数</li>
 *   <li>{@code defaultFee} / {@code dustThreshold} - 默认手续费和粉尘阈值</li>
 *   <li>{@code chainId} - 链 ID（EVM 兼容链使用）</li>
 *   <li>{@code gasPolicy} - EVM 交易信封与 Gas 报价策略</li>
 *   <li>{@code feeModel} - EVM 总费用模型（执行费、L1 数据费、Operator Fee）</li>
 *   <li>{@code scanBatchSize} / {@code scanMaxBlocksPerRun} - 扫描批次大小和最大区块范围</li>
 *   <li>{@code scanEnabled} / {@code withdrawEnabled} / {@code collectionEnabled} / {@code transferEnabled} - 功能开关</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountChainProfile {
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code network}，表示链、网络、资产或代币配置。
     */
    private String network;
    /**
     * 保存 {@code family}，表示链、网络、资产或代币配置。
     */
    private String family;
    /**
     * 保存 {@code runtimeCurrencyId}，表示链、网络、资产或代币配置。
     */
    private Integer runtimeCurrencyId;
    /**
     * 保存 {@code bip44CoinType}，表示链、网络、资产或代币配置。
     */
    private Integer bip44CoinType;
    /**
     * 保存 {@code nativeSymbol}，表示链、网络、资产或代币配置。
     */
    private String nativeSymbol;
    /**
     * 保存 {@code rpcUrl}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private String rpcUrl;
    /**
     * 保存 {@code explorerUrl}，用于承载当前对象的运行配置或业务数据。
     */
    private String explorerUrl;
    /**
     * 保存 {@code depositConfirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    private Integer depositConfirmations;
    /**
     * 保存 {@code withdrawConfirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    private Integer withdrawConfirmations;
    /**
     * 保存 {@code defaultFee}，用于保存金额、费用或链上执行状态。
     */
    private Long defaultFee;
    /**
     * 保存 {@code dustThreshold}，表示金额、余额、手续费、Gas 或精度相关参数。
     */
    private Long dustThreshold;
    /**
     * 保存 {@code enabled}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean enabled;
    /**
     * 保存 {@code chainId}，表示链、网络、资产或代币配置。
     */
    private Long chainId;
    /**
     * 保存 {@code accountClassHash}，用于 Starknet 账户合约的确定性地址和部署校验。
     */
    private String accountClassHash;
    /**
     * 保存 {@code gasPolicy}，用于保存运行配置和策略参数。
     */
    private String gasPolicy;
    /**
     * 保存 {@code feeModel}，用于保存金额、费用或链上执行状态。
     */
    private String feeModel;
    /**
     * 保存 {@code scanBatchSize}，记录开关、处理状态、确认结果或重试信息。
     */
    private Integer scanBatchSize;
    /**
     * 保存 {@code scanEnabled}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean scanEnabled;
    /**
     * 保存 {@code withdrawEnabled}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean withdrawEnabled;
    /**
     * 保存 {@code collectionEnabled}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean collectionEnabled;
    /**
     * 保存 {@code transferEnabled}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean transferEnabled;
    /**
     * 保存 {@code scanStartHeight}，记录开关、处理状态、确认结果或重试信息。
     */
    private Long scanStartHeight;
    /**
     * 保存 {@code scanMaxBlocksPerRun}，记录开关、处理状态、确认结果或重试信息。
     */
    private Long scanMaxBlocksPerRun;
}
