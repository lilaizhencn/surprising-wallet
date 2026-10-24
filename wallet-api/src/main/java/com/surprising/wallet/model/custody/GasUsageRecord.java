package com.surprising.wallet.model.custody;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Gas 使用记录。
 */
public record GasUsageRecord(
        UUID id,
        UUID tenantId,
        UUID gasAccountId,
        String operationType,
        UUID operationId,
        String referenceNo,
        String chain,
        String nativeSymbol,
        BigDecimal reservedAmount,
        BigDecimal actualAmount,
        String status,
        String pricingSource,
        String txHash,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant settledAt
) {
}
