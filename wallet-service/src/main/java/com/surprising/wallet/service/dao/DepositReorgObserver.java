package com.surprising.wallet.service.dao;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Transactional projection hook for a credited deposit that left the canonical chain.
 */
public interface DepositReorgObserver {
    void onDepositReorged(ReorgedDeposit deposit);
    record ReorgedDeposit(
            long depositRecordId,
            UUID tenantId,
            String chain,
            String assetSymbol,
            String txHash,
            long logIndex,
            String accountId,
            String toAddress,
            BigDecimal amount,
            BigDecimal reversedAmount,
            BigDecimal deficitAmount,
            int creditGeneration,
            long blockHeight,
            String blockHash,
            String replacementBlockHash,
            String reason
    ) {
    }
}
