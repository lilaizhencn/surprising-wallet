package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Starknet 交易记录，保存账户部署、原生币转账和 ERC-20 转账的审计信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StarknetTransactionRecord implements Serializable {
    /** 数据库主键。 */
    private Long id;
    /** 链标识。 */
    private String chain;
    /** Starknet 交易哈希。 */
    private String txHash;
    /** 发送账户地址。 */
    private String fromAddress;
    /** 接收账户或合约地址。 */
    private String toAddress;
    /** 资产符号。 */
    private String assetSymbol;
    /** ERC-20 合约地址，原生 STRK 为空。 */
    private String contractAddress;
    /** 转账金额。 */
    private BigDecimal amount;
    /** 实际支付的 STRK 手续费。 */
    private BigDecimal fee;
    /** 交易所在区块高度。 */
    private Long blockHeight;
    /** 当前确认数。 */
    private Integer confirmations;
    /** 交易状态。 */
    private String status;
    /** RPC 响应摘要，用于审计和排障。 */
    private String rawPayload;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
}
