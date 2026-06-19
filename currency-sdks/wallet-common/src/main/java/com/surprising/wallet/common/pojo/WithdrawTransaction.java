package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @author lilaizhen
 * @date 2018-04-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WithdrawTransaction implements Serializable {
    /**
     *
     */
    private Integer id;
    /**
     *
     */
    private String txId;
    /**
     * 此笔交易的金额
     */
    private BigDecimal balance;

    //hessian 在传输过程中BigDecimal精度会丢失，转化成 String
    private String balanceStr;
    /**
     *
     */
    private String signature;
    /**
     *
     */
    private Integer currency;
    /**
     * 0:正在签名;1:已发送;2:已确认
     */
    private Short status;
    /**
     *
     */
    private Date createDate;
    /**
     *
     */
    private Date updateDate;
}