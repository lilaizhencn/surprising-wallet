package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Runtime withdrawal order stored in withdrawal_order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawalOrderRecord {
    private Long id;
    private String orderNo;
    private Long userId;
    private String chain;
    private String assetSymbol;
    private String fromAddress;
    private String toAddress;
    private BigDecimal amount;
    private BigDecimal fee;
    private String txHash;
    private String status;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
