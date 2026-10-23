package com.surprising.wallet.model;

import java.math.BigDecimal;
import java.util.UUID;

public record ReorgedDeposit(long depositRecordId, UUID tenantId, String chain, String assetSymbol,
                             String txHash, long logIndex, String accountId, String toAddress,
                             BigDecimal amount, BigDecimal reversedAmount, BigDecimal deficitAmount,
                             int creditGeneration, long blockHeight, String blockHash,
                             String replacementBlockHash, String reason) { }
