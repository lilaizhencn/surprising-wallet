package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @author lilaizhen
 * @date 2018-04-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CurrencyBalance implements Serializable {
    /**
     *
     */
    private Integer id;
    /**
     *
     */
    private Integer currencyIndex;
    /**
     *
     */
    private BigDecimal balance;
    /**
     *
     */
    private Date createDate;
    /**
     *
     */
    private Date updateDate;
}