package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
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
    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Long id;
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code assetSymbol}，表示链、网络、资产或代币配置。
     */
    private String assetSymbol;
    /**
     * 保存 {@code accountId}，用于标识交易、区块或业务记录。
     */
    private String accountId;
    /**
     * 保存 {@code availableBalance}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal availableBalance;
    /**
     * 保存 {@code lockedBalance}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal lockedBalance;
    /**
     * 保存 {@code totalBalance}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal totalBalance;
    /**
     * 保存 {@code createdAt}，用于记录时间边界或审计时间。
     */
    private Instant createdAt;
    /**
     * 保存 {@code updatedAt}，用于记录时间边界或审计时间。
     */
    private Instant updatedAt;
}
