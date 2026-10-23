package com.surprising.wallet.chain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Public Gas usage query fields; no database Map or transport-specific conversion is needed. */
public record CustodyGasUsageEntry(UUID id, String operationType, UUID operationId, String referenceNo,
                                   String chain, String nativeSymbol, BigDecimal reservedAmount,
                                   BigDecimal actualAmount, String status, String pricingSource, String txHash,
                                   String errorMessage, Instant createdAt, Instant updatedAt, Instant settledAt) { }
