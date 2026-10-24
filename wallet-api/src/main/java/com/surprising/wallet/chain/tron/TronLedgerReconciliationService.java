package com.surprising.wallet.chain.tron;

import java.math.BigDecimal;

public class TronLedgerReconciliationService {
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
