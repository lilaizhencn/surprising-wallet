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
public class WithdrawTransaction implements Serializable {

    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Integer id;

    /**
     * 保存 {@code txId}，用于标识交易、区块或业务记录。
     */
    private String txId;
    /**
     * 此笔交易的金额
     */
    private BigDecimal balance;

    //hessian 在传输过程中BigDecimal精度会丢失，转化成 String
    private String balanceStr;

    /**
     * 保存 {@code signature}，用于保存签名、认证或密钥相关材料。
     */
    private String signature;

    /**
     * 保存 {@code currency}，表示链、网络、资产或代币配置。
     */
    private Integer currency;
        /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code assetSymbol}，表示链、网络、资产或代币配置。
     */
    private String assetSymbol;
    /**
     * 保存 {@code assetDecimals}，表示金额、余额、手续费、Gas 或精度相关参数。
     */
    private Integer assetDecimals;
    /**
     * 保存 {@code bip44CoinType}，表示链、网络、资产或代币配置。
     */
    private Integer bip44CoinType;
    /**
     * 保存 {@code contractAddress}，表示链、网络、资产或代币配置。
     */
    private String contractAddress;
    /**
     * 0:正在签名;1:已发送;2:已确认
     */
    private Short status;

    /**
     * 保存 {@code createDate}，用于记录时间边界或审计时间。
     */
    private Date createDate;

    /**
     * 保存 {@code updateDate}，用于记录时间边界或审计时间。
     */
    private Date updateDate;
}
