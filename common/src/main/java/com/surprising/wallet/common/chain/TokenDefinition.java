package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenDefinition implements Serializable {
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
     * 保存 {@code contractAddress}，表示链、网络、资产或代币配置。
     */
    private String contractAddress;
    /**
     * 保存 {@code decimals}，表示金额、余额、手续费、Gas 或精度相关参数。
     */
    private Integer decimals;
    /**
     * 保存 {@code standard}，用于承载当前对象的运行配置或业务数据。
     */
    private String standard;
    /**
     * 保存 {@code nativeAsset}，表示链、网络、资产或代币配置。
     */
    private Boolean nativeAsset;
    /**
     * 保存 {@code active}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean active;
}
