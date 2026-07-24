package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

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
    private Long id;
    private String chain;
    private String address;
    private Long chainNonce;
    private Long reservedNonce;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
