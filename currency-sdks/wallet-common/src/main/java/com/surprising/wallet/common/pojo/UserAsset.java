package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserAsset implements Serializable {
    private Long id;
    private Long userId;
    private Integer currency;
    private BigDecimal balance;
    private BigDecimal frozen;
    private Integer version;
    private Date createDate;
    private Date updateDate;
}
