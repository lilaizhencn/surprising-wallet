package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AptosTransactionRecord {
    private String chain;
    private String txHash;
    private String sender;
    private String receiver;
    private String assetSymbol;
    private String coinType;
    private BigDecimal amount;
    private Long gasUsed;
    private Long gasUnitPrice;
    private Long version;
    private Long sequenceNumber;
    private Integer confirmations;
    private String status;
    private String rawPayload;
}
