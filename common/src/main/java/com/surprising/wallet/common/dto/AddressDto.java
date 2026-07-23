package com.surprising.wallet.common.dto;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * @author lilaizhen
 * @data 05/04/2018
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AddressDto implements Serializable {

    /**
     * 用户id
     */
    private Long userId;
    /**
     * 钱包地址
     */
    private String address;
    /**
     * 业务类型
     */
    private Integer biz;
    private Integer childId;
    private String path;
    private String network;
    private String scriptType;
    private String redeemScript;
    private String witnessScript;
    private String publicKeys;
    /**
     * 充值确认数
     */
    private Integer depositConfirm;
    /**
     * 最小充值金额
     */
    private BigDecimal minDepositAmount;
}
