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
 * Solana 交易记录，保存 Solana 区块链上的充提交易数据。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code signature} - 交易签名</li>
 *   <li>{@code fromAddress} / {@code toAddress} - 发送方/接收方地址</li>
 *   <li>{@code assetSymbol} / {@code mintAddress} - 资产符号和 SPL Token Mint 地址</li>
 *   <li>{@code amount} / {@code feeLamports} - 金额和手续费（Lamports）</li>
 *   <li>{@code slot} / {@code confirmations} - 槽位号和确认数</li>
 *   <li>{@code status} - 交易状态</li>
 *   <li>{@code rawPayload} - 原始交易数据</li>
 * </ul>
 */
@AllArgsConstructor
public class SolanaTransactionRecord {
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code signature}，用于保存签名、认证或密钥相关材料。
     */
    private String signature;
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
     * 保存 {@code mintAddress}，表示链、网络、资产或代币配置。
     */
    private String mintAddress;
    /**
     * 保存 {@code amount}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal amount;
    /**
     * 保存 {@code feeLamports}，用于保存金额、费用或链上执行状态。
     */
    private Long feeLamports;
    /**
     * 保存 {@code slot}，用于承载当前对象的运行配置或业务数据。
     */
    private Long slot;
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
