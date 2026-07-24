package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
/**
 * TON（The Open Network）交易记录，保存 TON 区块链上的充提交易数据。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} - 链标识</li>
 *   <li>{@code txHash} - 交易哈希</li>
 *   <li>{@code fromAddress} / {@code toAddress} - 发送方/接收方地址</li>
 *   <li>{@code assetSymbol} / {@code jettonMaster} - 资产符号和 Jetton Master 合约地址</li>
 *   <li>{@code amount} / {@code feeNano} - 金额和手续费（nanoTON）</li>
 *   <li>{@code logicalTime} - 逻辑时间（TON 的全局排序时间戳）</li>
 *   <li>{@code confirmations} - 确认数</li>
 *   <li>{@code status} - 交易状态</li>
 *   <li>{@code rawPayload} - 原始交易数据</li>
 * </ul>
 */
@AllArgsConstructor
public class TonTransactionRecord {
    private String chain;
    private String txHash;
    private String fromAddress;
    private String toAddress;
    private String assetSymbol;
    private String jettonMaster;
    private BigDecimal amount;
    private Long feeNano;
    private BigInteger logicalTime;
    private Integer confirmations;
    private String status;
    private String rawPayload;
}
