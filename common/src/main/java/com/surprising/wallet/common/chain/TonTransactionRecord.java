package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TonTransactionRecord {
    private String chain;
    private String txHash;
    private String fromAddress;
    private String toAddress;
    private String assetSymbol;
    private String jettonMaster;
    private BigDecimal amount;
    private Long feeNano;
    private BigInteger logicalTime;
    private Integer confirmations;
    private String status;
    private String rawPayload;
}
