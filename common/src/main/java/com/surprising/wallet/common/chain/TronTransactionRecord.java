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
 * Tron（波场）交易记录，保存 Tron 区块链上的充提交易数据。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code txHash} - 交易哈希</li>
 *   <li>{@code fromAddress} / {@code toAddress} - 发送方/接收方地址</li>
 *   <li>{@code assetSymbol} / {@code contractAddress} - 资产符号和合约地址（TRC20）</li>
 *   <li>{@code amount} / {@code fee} - 金额和手续费</li>
 *   <li>{@code blockHeight} / {@code confirmations} - 区块高度和确认数</li>
 *   <li>{@code status} - 交易状态</li>
 *   <li>{@code rawPayload} - 原始交易数据</li>
 * </ul>
 */
@AllArgsConstructor
public class TronTransactionRecord implements Serializable {
    private Long id;
    private String chain;
    private String txHash;
    private String fromAddress;
    private String toAddress;
    private String assetSymbol;
    private String contractAddress;
    private BigDecimal amount;
    private BigDecimal fee;
    private Long blockHeight;
    private Integer confirmations;
    private String status;
    private String rawPayload;
    private Instant createdAt;
    private Instant updatedAt;
}
