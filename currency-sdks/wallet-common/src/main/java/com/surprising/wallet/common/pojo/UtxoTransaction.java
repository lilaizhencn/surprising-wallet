package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @author lilaizhen
 * @date 2018-03-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UtxoTransaction implements Serializable {
    /**
     *
     */
    private Long id;
    /**
     *
     */
    private String txId;
    /**
     *
     */
    private Long blockHeight;
    /**
     *
     */
    private String address;
    /**
     *
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
     *
     */
    private Date createDate;
    /**
     *
     */
    private Date updateDate;
    /**
     * 业务类型
     */
    private Integer biz;

    private Integer currency;

    private Byte status;

    private Boolean credited;
}
