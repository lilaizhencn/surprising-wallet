package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainAsset implements Serializable {
    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Long id;
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code symbol}，表示链、网络、资产或代币配置。
     */
    private String symbol;
    /**
     * 保存 {@code assetKind}，表示链、网络、资产或代币配置。
     */
    private String assetKind;
    /**
     * 保存 {@code contractAddress}，表示链、网络、资产或代币配置。
     */
    private String contractAddress;
    /**
     * 保存 {@code decimals}，表示金额、余额、手续费、Gas 或精度相关参数。
     */
    private Integer decimals;
    /**
     * 保存 {@code nativeAsset}，表示链、网络、资产或代币配置。
     */
    private Boolean nativeAsset;
    /**
     * 保存 {@code active}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean active;
    /**
     * 保存 {@code minTransfer}，记录开关、处理状态、确认结果或重试信息。
     */
    private BigDecimal minTransfer;
    /**
     * 保存 {@code minWithdraw}，记录开关、处理状态、确认结果或重试信息。
     */
    private BigDecimal minWithdraw;
    /**
     * 保存 {@code createdAt}，用于记录时间边界或审计时间。
     */
    private Instant createdAt;
    /**
     * 保存 {@code updatedAt}，用于记录时间边界或审计时间。
     */
    private Instant updatedAt;
}
