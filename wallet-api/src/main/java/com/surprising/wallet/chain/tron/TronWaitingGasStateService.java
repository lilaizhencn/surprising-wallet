package com.surprising.wallet.chain.tron;

import java.math.BigDecimal;

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
