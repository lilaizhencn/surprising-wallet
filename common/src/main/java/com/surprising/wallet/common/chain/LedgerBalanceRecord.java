package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerBalanceRecord implements Serializable {
    private Long id;
    private String chain;
    private String assetSymbol;
    private String accountId;
    private BigDecimal availableBalance;
    private BigDecimal lockedBalance;
    private BigDecimal totalBalance;
    private Instant createdAt;
    private Instant updatedAt;
}
