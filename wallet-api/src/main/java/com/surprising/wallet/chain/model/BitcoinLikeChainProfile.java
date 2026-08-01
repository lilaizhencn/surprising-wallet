package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BitcoinLikeChainProfile {
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
     * 保存 {@code defaultFeeRate}，用于保存金额、费用或链上执行状态。
     */
    private Long defaultFeeRate;
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
     * 保存 {@code gasPolicy}，用于保存运行配置和策略参数。
     */
    private String gasPolicy;
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
