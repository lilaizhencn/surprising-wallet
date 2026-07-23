package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds atomic BTC settlement plans with isolated user outputs.
 */
public final class MultiUserSettlementPlanner {
    private final UtxoOptimizer optimizer;

    public MultiUserSettlementPlanner() {
        this(new UtxoOptimizer());
    }

    public MultiUserSettlementPlanner(UtxoOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    public BatchSettlementPlan plan(List<UtxoCandidate> candidates, List<WithdrawSettlementOutput> outputs,
                                    long feeRateSatPerVByte, long dustThresholdSat) {
        ArrayList<WithdrawSettlementOutput> normalized = new ArrayList<>(outputs);
        normalized.sort(Comparator.comparingLong(WithdrawSettlementOutput::getUserId)
                .thenComparing(WithdrawSettlementOutput::getAddress)
                .thenComparingLong(WithdrawSettlementOutput::getSatoshis));
        return optimizer.planBatch(candidates, normalized, feeRateSatPerVByte, dustThresholdSat);
    }
}
