package com.surprising.wallet.chain.tron;

import java.math.BigDecimal;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
public class TronLedgerReconciliationService {
    /**
     * 执行 {@code compare} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public ReconciliationResult compare(String chain, String asset, String address,
                                        BigDecimal ledgerBalance, BigDecimal chainBalance) {
        BigDecimal delta = chainBalance.subtract(ledgerBalance);
        return new ReconciliationResult(chain, asset, address, ledgerBalance, chainBalance,
                delta, delta.compareTo(BigDecimal.ZERO) == 0);
    }

    public record ReconciliationResult(String chain, String asset, String address,
                                       BigDecimal ledgerBalance, BigDecimal chainBalance,
                                       BigDecimal delta, boolean matched) {
    }
}
