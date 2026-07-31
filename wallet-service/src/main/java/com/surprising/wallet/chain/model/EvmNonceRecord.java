package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
@Data
@Builder
@NoArgsConstructor
/**
 * EVM 链 nonce 记录，追踪各地址在 EVM 兼容链上的交易 nonce 使用情况。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code address} - 链上地址</li>
 *   <li>{@code chainNonce} - 链上当前 nonce 值</li>
 *   <li>{@code reservedNonce} - 本地已预留的 nonce 值</li>
 *   <li>{@code status} - nonce 状态</li>
 * </ul>
 */
@AllArgsConstructor
public class EvmNonceRecord implements Serializable {
    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Long id;
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private String address;
    /**
     * 保存 {@code chainNonce}，表示链、网络、资产或代币配置。
     */
    private Long chainNonce;
    /**
     * 保存 {@code reservedNonce}，用于保存金额、费用或链上执行状态。
     */
    private Long reservedNonce;
    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    private String status;
    /**
     * 保存 {@code createdAt}，用于记录时间边界或审计时间。
     */
    private Instant createdAt;
    /**
     * 保存 {@code updatedAt}，用于记录时间边界或审计时间。
     */
    private Instant updatedAt;
}
