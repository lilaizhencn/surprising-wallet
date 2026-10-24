package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Address implements Serializable {

    private Integer id;

    private Long userId;

    private String address;

    private String network;

    private String scriptType;

    private String redeemScript;

    private String witnessScript;

    private String derivationPath;

    /**
     * 保存 {@code publicKeys}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private String publicKeys;

    /**
     * 币种
     */
    private String currency;

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

    private Byte status;

    private Date createDate;

    private Date updateDate;
}
