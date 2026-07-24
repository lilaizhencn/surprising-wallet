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
    private String chain;
    private String signature;
    private String fromAddress;
    private String toAddress;
    private String assetSymbol;
    private String mintAddress;
    private BigDecimal amount;
    private Long feeLamports;
    private Long slot;
    private Integer confirmations;
    private String status;
    private String rawPayload;
}
