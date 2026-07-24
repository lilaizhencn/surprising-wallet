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
 * Sui 交易记录，保存 Sui 区块链上的充提交易数据。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code txDigest} - 交易摘要（Sui 中使用 digest 代替 hash）</li>
 *   <li>{@code sender} / {@code receiver} - 发送方/接收方地址</li>
 *   <li>{@code assetSymbol} / {@code coinType} - 资产符号和 Coin 类型</li>
 *   <li>{@code amount} / {@code gasUsed} - 金额和消耗的 Gas</li>
 *   <li>{@code checkpoint} - 检查点序号</li>
 *   <li>{@code status} - 交易状态</li>
 *   <li>{@code rawPayload} - 原始交易数据</li>
 * </ul>
 */
@AllArgsConstructor
public class SuiTransactionRecord {
    private String chain;
    private String txDigest;
    private String sender;
    private String receiver;
    private String assetSymbol;
    private String coinType;
    private BigDecimal amount;
    private Long gasUsed;
    private Long checkpoint;
    private String status;
    private String rawPayload;
}
