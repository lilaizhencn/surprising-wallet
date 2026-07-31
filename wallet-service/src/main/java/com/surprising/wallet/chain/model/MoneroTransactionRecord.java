package com.surprising.wallet.chain.model;

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
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    String chain;
    /**
     * 保存 {@code txHash}，用于标识交易、区块或业务记录。
     */
    String txHash;
    /**
     * 保存 {@code direction}，用于承载当前对象的运行配置或业务数据。
     */
    String direction;
    /**
     * 保存 {@code accountIndex}，用于承载当前对象的运行配置或业务数据。
     */
    Integer accountIndex;
    /**
     * 保存 {@code subaddressIndex}，表示链、网络、资产或代币配置。
     */
    Integer subaddressIndex;
    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    String address;
    /**
     * 保存 {@code assetSymbol}，表示链、网络、资产或代币配置。
     */
    String assetSymbol;
    /**
     * 保存 {@code amount}，用于保存金额、费用或链上执行状态。
     */
    BigDecimal amount;
    /**
     * 保存 {@code feeAtomic}，用于保存金额、费用或链上执行状态。
     */
    Long feeAtomic;
    /**
     * 保存 {@code blockHeight}，用于标识交易、区块或业务记录。
     */
    Long blockHeight;
    /**
     * 保存 {@code confirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    Integer confirmations;
    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    String status;
    /**
     * 保存 {@code rawPayload}，用于承载当前对象的运行配置或业务数据。
     */
    String rawPayload;
}
