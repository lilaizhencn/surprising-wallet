package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainScanHeightRecord {
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code scannerName}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private String scannerName;
    /**
     * 保存 {@code bestHeight}，用于标识交易、区块或业务记录。
     */
    private Long bestHeight;
    /**
     * 保存 {@code safeHeight}，用于标识交易、区块或业务记录。
     */
    private Long safeHeight;
    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    private String status;
    /**
     * 保存 {@code updatedAt}，用于记录时间边界或审计时间。
     */
    private Instant updatedAt;
}
