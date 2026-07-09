package com.surprising.wallet.service.chain.tron;

import java.math.BigDecimal;

/**
 * Encapsulates the WAITING_GAS decision for TRC20 withdrawals and collections.
 * Business funds remain locked while this state is active; only a bounded TRX
 * top-up task is allowed to move the original task forward.
 */
public class TronWaitingGasStateService {
    public WaitingGasDecision evaluate(String chain, String taskNo, String address,
                                       BigDecimal currentTrxBalance,
                                       BigDecimal estimatedRequiredTrx,
                                       TronGasPolicy policy) {
        TronGasEstimator.GasDecision gasDecision = new TronGasEstimator()
                .decideTopup(currentTrxBalance, estimatedRequiredTrx, policy);
        if (!gasDecision.waitingGas()) {
            return new WaitingGasDecision(false, null, BigDecimal.ZERO, "gas sufficient");
        }
        String gasTaskNo = chain + "-GAS-" + taskNo + "-" + address.toLowerCase();
        return new WaitingGasDecision(true, gasTaskNo, gasDecision.topupAmount(), gasDecision.reason());
    }

    public record WaitingGasDecision(boolean waitingGas, String gasTaskNo,
                                     BigDecimal topupAmount, String reason) {
    }
}
