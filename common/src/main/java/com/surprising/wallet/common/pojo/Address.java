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
public class Address implements Serializable {

    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Integer id;

    /**
     * 保存 {@code userId}，用于标识交易、区块或业务记录。
     */
    private Long userId;

    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private String address;

    /**
     * 保存 {@code network}，表示链、网络、资产或代币配置。
     */
    private String network;

    /**
     * 保存 {@code scriptType}，用于承载当前对象的运行配置或业务数据。
     */
    private String scriptType;

    /**
     * 保存 {@code redeemScript}，用于承载当前对象的运行配置或业务数据。
     */
    private String redeemScript;

    /**
     * 保存 {@code witnessScript}，用于承载当前对象的运行配置或业务数据。
     */
    private String witnessScript;

    /**
     * 保存 {@code derivationPath}，用于承载当前对象的运行配置或业务数据。
     */
    private String derivationPath;

    /**
     * 保存 {@code publicKeys}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private String publicKeys;

    /**
     * 币种
     */
    private String currency;

    /**
     * 保存 {@code balance}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal balance;
    /**
     * 业务类型
     */
    private Integer biz;
    /**
     * 账户类型的币发送交易时需要nounce
     */
    private Integer nonce;
    /**
     * userId生成的第几个地址
     */
    private Integer index;

    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    private Byte status;

    /**
     * 保存 {@code createDate}，用于记录时间边界或审计时间。
     */
    private Date createDate;

    /**
     * 保存 {@code updateDate}，用于记录时间边界或审计时间。
     */
    private Date updateDate;
}
