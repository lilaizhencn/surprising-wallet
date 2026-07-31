package com.surprising.wallet.common.dto;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TransactionDTO implements Serializable {

    /**
     * 保存 {@code txId}，用于标识交易、区块或业务记录。
     */
    private String txId;

    /**
     * 保存 {@code blockHeight}，用于标识交易、区块或业务记录。
     */
    private Long blockHeight;
    /**
     * 保存 {@code blockHash}，用于标识交易、区块或业务记录。
     */
    private String blockHash;

    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private String address;

    /**
     * 保存 {@code currency}，表示链、网络、资产或代币配置。
     */
    private Integer currency;

    /**
     * 保存 {@code balance}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal balance;
    /**
     * 确认数
     */
    private Long confirmNum;

    /**
     * 业务线
     */
    private Integer biz;


}
