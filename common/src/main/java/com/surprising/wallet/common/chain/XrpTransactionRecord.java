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
 * XRP（Ripple）交易记录，保存 XRP Ledger 上的充提交易数据。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code txHash} - 交易哈希</li>
 *   <li>{@code fromAddress} / {@code toAddress} - 发送方/接收方地址</li>
 *   <li>{@code assetSymbol} / {@code issuerAddress} / {@code currencyCode} - 资产符号、发行方地址和货币代码</li>
 *   <li>{@code amount} / {@code feeDrops} - 金额和手续费（Drops 单位）</li>
 *   <li>{@code ledgerIndex} / {@code sequenceNumber} - 账本索引和序列号</li>
 *   <li>{@code confirmations} - 确认数</li>
 *   <li>{@code status} - 交易状态</li>
 *   <li>{@code rawPayload} - 原始交易数据</li>
 * </ul>
 */
@AllArgsConstructor
public class XrpTransactionRecord {
    private String chain;
    private String txHash;
    private String fromAddress;
    private String toAddress;
    private String assetSymbol;
    private String issuerAddress;
    private String currencyCode;
    private BigDecimal amount;
    private Long feeDrops;
    private Long ledgerIndex;
    private Long sequenceNumber;
    private Integer confirmations;
    private String status;
    private String rawPayload;
}
