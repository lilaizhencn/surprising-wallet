package com.surprising.wallet.chain.model;

import com.surprising.wallet.common.chain.ChainType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainProfile {
    /**
     * 保存 {@code chainType}，表示链、网络、资产或代币配置。
     */
    private ChainType chainType;
    /**
     * 保存 {@code rpcUrl}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private String rpcUrl;
    /**
     * 保存 {@code chainId}，表示链、网络、资产或代币配置。
     */
    private Long chainId;
    /**
     * 保存 {@code defaultGasLimit}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal defaultGasLimit;
    /**
     * 保存 {@code gasPriceFloor}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal gasPriceFloor;
    /**
     * 保存 {@code priorityFee}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal priorityFee;
    /**
     * 保存 {@code depositConfirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    private Integer depositConfirmations;
    /**
     * 保存 {@code withdrawConfirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    private Integer withdrawConfirmations;
    /**
     * 保存 {@code nativeSymbol}，表示链、网络、资产或代币配置。
     */
    private String nativeSymbol;
}
