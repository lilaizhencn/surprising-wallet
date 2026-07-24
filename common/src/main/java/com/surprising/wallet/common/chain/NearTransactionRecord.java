package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
/**
 * NEAR 协议交易记录，保存 NEAR 区块链上的充提交易数据。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code txHash} - 交易哈希</li>
 *   <li>{@code actionIndex} - Action 索引（NEAR 交易可包含多个 Action）</li>
 *   <li>{@code sender} / {@code receiver} - 发送方/接收方地址</li>
 *   <li>{@code assetSymbol} - 资产符号</li>
 *   <li>{@code amount} / {@code gasBurnt} - 金额和消耗的 Gas</li>
 *   <li>{@code blockHeight} - 区块高度</li>
 *   <li>{@code status} - 交易状态</li>
 *   <li>{@code rawPayload} - 原始交易数据</li>
 * </ul>
 */
@AllArgsConstructor
public class NearTransactionRecord {
    private String chain;
    private String txHash;
    private Long actionIndex;
    private String sender;
    private String receiver;
    private String assetSymbol;
    private BigDecimal amount;
    private Long gasBurnt;
    private Long blockHeight;
    private String status;
    private String rawPayload;
}
