package com.surprising.wallet.model.custody;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReorgDeficitRecord(UUID id, UUID custodyAddressId, String chain, String assetSymbol,
                                 BigDecimal deficitAmount, BigDecimal recoveredAmount, Instant createdAt) {
    public BigDecimal outstandingAmount() { return deficitAmount.subtract(recoveredAmount); }
}
