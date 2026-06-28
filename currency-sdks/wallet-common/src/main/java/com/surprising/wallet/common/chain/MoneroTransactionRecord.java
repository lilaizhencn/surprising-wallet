package com.surprising.wallet.common.chain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class MoneroTransactionRecord {
    String chain;
    String txHash;
    String direction;
    Integer accountIndex;
    Integer subaddressIndex;
    String address;
    String assetSymbol;
    BigDecimal amount;
    Long feeAtomic;
    Long blockHeight;
    Integer confirmations;
    String status;
    String rawPayload;
}
