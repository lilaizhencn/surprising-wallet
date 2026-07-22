package com.surprising.wallet.common.dto;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * @author lilaizhen
 * @data 31/03/2018
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TransactionDTO implements Serializable {
    /**
     *
     */
    private String txId;
    /**
     *
     */
    private Long blockHeight;
    private String blockHash;
    /**
     *
     */
    private String address;

    private Integer currency;
    /**
     *
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
