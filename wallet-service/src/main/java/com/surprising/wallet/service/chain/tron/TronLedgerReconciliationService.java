package com.surprising.wallet.service.chain.tron;

import java.math.BigDecimal;

/**
 * Compares chain balances and ledger balances with exact decimal arithmetic.
 * Scanner/withdraw/collection jobs should emit an alert when matched is false.
 */
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
