package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @author lilaizhen
 * @date 2018-03-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Address implements Serializable {
    /**
     *
     */
    private Integer id;
    /**
     *
     */
    private Long userId;
    /**
     *
     */
    private String address;

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
