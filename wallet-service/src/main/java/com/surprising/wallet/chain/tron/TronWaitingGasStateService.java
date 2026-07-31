package com.surprising.wallet.chain.tron;

import java.math.BigDecimal;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
public class TronWaitingGasStateService {
    /**
     * 执行 {@code evaluate} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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
