package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainCollectionRecord {
    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Long id;
    /**
     * 保存 {@code tenantId}，用于标识交易、区块或业务记录。
     */
    private UUID tenantId;
    /**
     * 保存 {@code custodyAddressId}，表示链、网络、资产或代币配置。
     */
    private UUID custodyAddressId;
    /**
     * 保存 {@code collectionNo}，记录开关、处理状态、确认结果或重试信息。
     */
    private String collectionNo;
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code assetSymbol}，表示链、网络、资产或代币配置。
     */
    private String assetSymbol;
    /**
     * 保存 {@code fromAddress}，表示链、网络、资产或代币配置。
     */
    private String fromAddress;
    /**
     * 保存 {@code toAddress}，表示链、网络、资产或代币配置。
     */
    private String toAddress;
    /**
     * 保存 {@code amount}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal amount;
    /**
     * 保存 {@code fee}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal fee;
    /**
     * 保存 {@code txHash}，用于标识交易、区块或业务记录。
     */
    private String txHash;
    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    private String status;
    /**
     * 保存 {@code errorMessage}，用于承载当前对象的运行配置或业务数据。
     */
    private String errorMessage;
    /**
     * 保存 {@code rawPayload}，用于承载当前对象的运行配置或业务数据。
     */
    private String rawPayload;
    /**
     * 保存 {@code createdAt}，用于记录时间边界或审计时间。
     */
    private Instant createdAt;
    /**
     * 保存 {@code updatedAt}，用于记录时间边界或审计时间。
     */
    private Instant updatedAt;
}
