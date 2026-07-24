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
/**
 * 账本余额记录，记录各账户在各链上的资产余额快照。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} / {@code assetSymbol} - 链标识和资产符号</li>
 *   <li>{@code accountId} - 账户 ID</li>
 *   <li>{@code availableBalance} - 可用余额</li>
 *   <li>{@code lockedBalance} - 锁定余额</li>
 *   <li>{@code totalBalance} - 总余额</li>
 * </ul>
 */
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
