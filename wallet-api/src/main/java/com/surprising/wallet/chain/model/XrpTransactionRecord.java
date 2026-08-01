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
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code txHash}，用于标识交易、区块或业务记录。
     */
    private String txHash;
    /**
     * 保存 {@code fromAddress}，表示链、网络、资产或代币配置。
     */
    private String fromAddress;
    /**
     * 保存 {@code toAddress}，表示链、网络、资产或代币配置。
     */
    private String toAddress;
    /**
     * 保存 {@code assetSymbol}，表示链、网络、资产或代币配置。
     */
    private String assetSymbol;
    /**
     * 保存 {@code issuerAddress}，表示链、网络、资产或代币配置。
     */
    private String issuerAddress;
    /**
     * 保存 {@code currencyCode}，表示链、网络、资产或代币配置。
     */
    private String currencyCode;
    /**
     * 保存 {@code amount}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal amount;
    /**
     * 保存 {@code feeDrops}，用于保存金额、费用或链上执行状态。
     */
    private Long feeDrops;
    /**
     * 保存 {@code ledgerIndex}，用于承载当前对象的运行配置或业务数据。
     */
    private Long ledgerIndex;
    /**
     * 保存 {@code sequenceNumber}，用于承载当前对象的运行配置或业务数据。
     */
    private Long sequenceNumber;
    /**
     * 保存 {@code confirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    private Integer confirmations;
    /**
     * 保存 {@code status}，记录开关、处理状态、确认结果或重试信息。
     */
    private String status;
    /**
     * 保存 {@code rawPayload}，用于承载当前对象的运行配置或业务数据。
     */
    private String rawPayload;
}
