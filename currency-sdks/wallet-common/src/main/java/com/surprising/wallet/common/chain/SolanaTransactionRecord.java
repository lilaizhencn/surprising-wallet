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
public class SolanaTransactionRecord {
    private String chain;
    private String signature;
    private String fromAddress;
    private String toAddress;
    private String assetSymbol;
    private String mintAddress;
    private BigDecimal amount;
    private Long feeLamports;
    private Long slot;
    private Integer confirmations;
    private String status;
    private String rawPayload;
}
