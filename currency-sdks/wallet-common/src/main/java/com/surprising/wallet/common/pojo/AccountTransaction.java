package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @author lilaizhen
 * @date 2018-04-12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AccountTransaction implements Serializable {
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
     * 业务类型
     */
    private Integer biz;

    private Integer currency;
    /**
     *
     */
    private Byte status;
    /**
     *
     */
    private Date createDate;
    /**
     *
     */
    private Date updateDate;
}