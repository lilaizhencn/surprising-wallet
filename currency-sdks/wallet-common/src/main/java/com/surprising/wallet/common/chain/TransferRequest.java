package com.surprising.wallet.common.chain;

import java.math.BigDecimal;

/**
 * Unified transfer request used by chain adapters.
 */
public record TransferRequest(
        ChainType chainType,
        String assetSymbol,
        String fromAddress,
        String toAddress,
        BigDecimal amount,
        Integer recipientCount,
        Long feeRateSatPerVByte,
        String memo
) {
    public TransferRequest {
        if (chainType == null) {
            throw new IllegalArgumentException("chainType must not be null");
        }
        if (assetSymbol == null || assetSymbol.isBlank()) {
            throw new IllegalArgumentException("assetSymbol must not be blank");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalArgumentException("fromAddress must not be blank");
        }
        if (toAddress == null || toAddress.isBlank()) {
            throw new IllegalArgumentException("toAddress must not be blank");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
