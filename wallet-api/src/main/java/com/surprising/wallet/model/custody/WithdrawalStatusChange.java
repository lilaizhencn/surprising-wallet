package com.surprising.wallet.model.custody;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 提现状态变化记录。
 */
public record WithdrawalStatusChange(
        UUID id,
        UUID tenantId,
        UUID custodyAddressId,
        String orderNo,
        String externalReference,
        String chain,
        String assetSymbol,
        String toAddress,
        BigDecimal amount,
        BigDecimal fee,
        String previousStatus,
        String nextStatus,
        String txHash,
        String errorMessage,
        String debitAccountId,
        String addressSource
) {
}
