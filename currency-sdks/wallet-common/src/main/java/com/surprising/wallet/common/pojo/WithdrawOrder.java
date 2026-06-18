package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WithdrawOrder implements Serializable {
    private Long id;
    private Long userId;
    private Integer currency;
    private String chain;
    private String fromAddress;
    private String toAddress;
    private BigDecimal amount;
    private BigDecimal fee;
    private Integer status;       // 0:待签名 1:已广播 2:已确认 3:失败
    private String txId;
    private String signatureData; // JSON payload for signing (utxos, addresses, etc.)
    private String remark;
    private Date createDate;
    private Date updateDate;
}
