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
public class SuiTransactionRecord {
    private String chain;
    private String txDigest;
    private String sender;
    private String receiver;
    private String assetSymbol;
    private String coinType;
    private BigDecimal amount;
    private Long gasUsed;
    private Long checkpoint;
    private String status;
    private String rawPayload;
}
