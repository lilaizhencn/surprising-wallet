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
 * Aptos 交易记录，保存 Aptos 区块链上的充提交易数据。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code txHash} - 交易哈希</li>
 *   <li>{@code sender} / {@code receiver} - 发送方/接收方地址</li>
 *   <li>{@code assetSymbol} / {@code coinType} - 资产符号和 Coin 类型</li>
 *   <li>{@code amount} / {@code gasUsed} / {@code gasUnitPrice} - 金额、消耗的 Gas 和 Gas 单价</li>
 *   <li>{@code version} / {@code sequenceNumber} - 交易版本号和序列号</li>
 *   <li>{@code confirmations} - 确认数</li>
 *   <li>{@code status} - 交易状态</li>
 *   <li>{@code rawPayload} - 原始交易数据</li>
 * </ul>
 */
@AllArgsConstructor
public class AptosTransactionRecord {
    private String chain;
    private String txHash;
    private String sender;
    private String receiver;
    private String assetSymbol;
    private String coinType;
    private BigDecimal amount;
    private Long gasUsed;
    private Long gasUnitPrice;
    private Long version;
    private Long sequenceNumber;
    private Integer confirmations;
    private String status;
    private String rawPayload;
}
