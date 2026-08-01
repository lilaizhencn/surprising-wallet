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
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code txHash}，用于标识交易、区块或业务记录。
     */
    private String txHash;
    /**
     * 保存 {@code actionIndex}，用于承载当前对象的运行配置或业务数据。
     */
    private Long actionIndex;
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
     * 保存 {@code amount}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal amount;
    /**
     * 保存 {@code gasBurnt}，用于保存金额、费用或链上执行状态。
     */
    private Long gasBurnt;
    /**
     * 保存 {@code blockHeight}，用于标识交易、区块或业务记录。
     */
    private Long blockHeight;
    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    private String status;
    /**
     * 保存 {@code rawPayload}，用于承载当前对象的运行配置或业务数据。
     */
    private String rawPayload;
}
