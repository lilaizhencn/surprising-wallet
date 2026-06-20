package com.surprising.wallet.common.chain;

import java.math.BigDecimal;

/**
 * Normalized deposit event detected during chain scanning.
 */
public record DepositEvent(
        ChainType chainType,
        String assetSymbol,
        String txId,
        String fromAddress,
        String toAddress,
        BigDecimal amount,
        long blockHeight,
        int confirmations,
        String tokenAddress,
        String rawPayload
) {
}
