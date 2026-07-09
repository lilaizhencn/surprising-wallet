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
public class NearTransactionRecord {
    private String chain;
    private String txHash;
    private Long actionIndex;
    private String sender;
    private String receiver;
    private String assetSymbol;
    private BigDecimal amount;
    private Long gasBurnt;
    private Long blockHeight;
    private String status;
    private String rawPayload;
}
