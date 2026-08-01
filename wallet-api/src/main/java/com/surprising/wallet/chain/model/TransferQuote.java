package com.surprising.wallet.chain.model;

import com.surprising.wallet.common.chain.ChainType;

import java.math.BigDecimal;

/**
 * Fee and nonce quote for a chain transfer.
 */
public record TransferQuote(
        ChainType chainType,
        String assetSymbol,
        String fromAddress,
        String toAddress,
        BigDecimal amount,
        BigDecimal fee,
        Long nonce,
        Long gasLimit,
        Long maxFeePerGas,
        Long priorityFeePerGas,
        String payload,
        boolean supported,
        String reason
) {
    /**
     * 执行 {@code unsupported} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static TransferQuote unsupported(ChainType chainType, String assetSymbol, String fromAddress,
                                            String toAddress, BigDecimal amount, String reason) {
        return new TransferQuote(chainType, assetSymbol, fromAddress, toAddress, amount, BigDecimal.ZERO,
                null, null, null, null, null, false, reason);
    }
}
