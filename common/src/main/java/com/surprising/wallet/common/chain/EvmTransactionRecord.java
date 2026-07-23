package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvmTransactionRecord implements Serializable {
    private Long id;
    private String chain;
    private String txHash;
    private String fromAddress;
    private String toAddress;
    private String assetSymbol;
    private String contractAddress;
    private BigDecimal amount;
    private BigDecimal fee;
    private Long nonce;
    private Long blockHeight;
    private Integer confirmations;
    private String status;
    private String rawPayload;
    private Instant createdAt;
    private Instant updatedAt;
}
