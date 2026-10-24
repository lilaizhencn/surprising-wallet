package com.surprising.wallet.model.custody;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Gas 账户记录。
 */
public record GasAccountRecord(
        UUID id,
        UUID tenantId,
        UUID custodyAddressId,
        String chain,
        String network,
        String nativeSymbol,
        String address,
        String memo,
        long childIndex,
        String accountId,
        BigDecimal availableBalance,
        BigDecimal lockedBalance,
        BigDecimal totalBalance,
        BigDecimal lowBalanceThreshold,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 判断 Gas 余额是否低于阈值。
     */
    public boolean lowBalance() {
        return "ACTIVE".equals(status) && availableBalance.compareTo(lowBalanceThreshold) < 0;
    }
}
