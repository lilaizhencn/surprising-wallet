package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
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
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code txDigest}，用于标识交易、区块或业务记录。
     */
    private String txDigest;
    /**
     * 保存 {@code sender}，用于承载当前对象的运行配置或业务数据。
     */
    private String sender;
    /**
     * 保存 {@code receiver}，用于承载当前对象的运行配置或业务数据。
     */
    private String receiver;
    /**
     * 保存 {@code assetSymbol}，表示链、网络、资产或代币配置。
     */
    private String assetSymbol;
    /**
     * 保存 {@code coinType}，表示链、网络、资产或代币配置。
     */
    private String coinType;
    /**
     * 保存 {@code amount}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal amount;
    /**
     * 保存 {@code gasUsed}，用于保存金额、费用或链上执行状态。
     */
    private Long gasUsed;
    /**
     * 保存 {@code checkpoint}，用于承载当前对象的运行配置或业务数据。
     */
    private Long checkpoint;
    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    private String status;
    /**
     * 保存 {@code rawPayload}，用于承载当前对象的运行配置或业务数据。
     */
    private String rawPayload;
}
