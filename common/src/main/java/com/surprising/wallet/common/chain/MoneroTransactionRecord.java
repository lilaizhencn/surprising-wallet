package com.surprising.wallet.common.chain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Monero（门罗币）交易记录，保存门罗链上的充提交易数据。
 *
 * <p>门罗链采用 UTXO 模型，使用 accountIndex 和 subaddressIndex 标识子地址。</p>
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code txHash} - 交易哈希</li>
 *   <li>{@code direction} - 交易方向（入金/出金）</li>
 *   <li>{@code accountIndex} / {@code subaddressIndex} - 账户索引和子地址索引</li>
 *   <li>{@code address} - 地址</li>
 *   <li>{@code assetSymbol} - 资产符号</li>
 *   <li>{@code amount} / {@code feeAtomic} - 金额和手续费（原子单位）</li>
 *   <li>{@code blockHeight} / {@code confirmations} - 区块高度和确认数</li>
 *   <li>{@code status} - 交易状态</li>
 *   <li>{@code rawPayload} - 原始交易数据</li>
 * </ul>
 */
@Value
@Builder
public class MoneroTransactionRecord {
    String chain;
    String txHash;
    String direction;
    Integer accountIndex;
    Integer subaddressIndex;
    String address;
    String assetSymbol;
    BigDecimal amount;
    Long feeAtomic;
    Long blockHeight;
    Integer confirmations;
    String status;
    String rawPayload;
}
