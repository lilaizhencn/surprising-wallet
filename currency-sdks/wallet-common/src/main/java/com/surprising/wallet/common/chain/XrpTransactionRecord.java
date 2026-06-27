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
public class XrpTransactionRecord {
    private String chain;
    private String txHash;
    private String fromAddress;
    private String toAddress;
    private String assetSymbol;
    private String issuerAddress;
    private String currencyCode;
    private BigDecimal amount;
    private Long feeDrops;
    private Long ledgerIndex;
    private Long sequenceNumber;
    private Integer confirmations;
    private String status;
    private String rawPayload;
}
