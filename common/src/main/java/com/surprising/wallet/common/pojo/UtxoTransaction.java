package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UtxoTransaction implements Serializable {

    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Long id;

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
     * 保存 {@code balance}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal balance;
    /**
     * 确认数
     */
    private Long confirmNum;
    /**
     * output序号
     */
    private Short seq;
    /**
     * 是否被花费
     */
    private Byte spent;
    /**
     * 花费此输出的txid
     */
    private String spentTxId;

    /**
     * 保存 {@code createDate}，用于记录时间边界或审计时间。
     */
    private Date createDate;

    /**
     * 保存 {@code updateDate}，用于记录时间边界或审计时间。
     */
    private Date updateDate;
    /**
     * 业务类型
     */
    private Integer biz;

    /**
     * 保存 {@code currency}，表示链、网络、资产或代币配置。
     */
    private Integer currency;

    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    private Byte status;

    /**
     * 保存 {@code credited}，用于承载当前对象的运行配置或业务数据。
     */
    private Boolean credited;
}
