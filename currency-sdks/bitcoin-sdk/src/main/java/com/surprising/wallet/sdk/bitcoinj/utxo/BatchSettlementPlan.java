package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.Collections;
import java.util.List;

/**
 * Atomic merged withdrawal plan for multiple BTC users.
 */
public final class BatchSettlementPlan {
    private final List<UtxoCandidate> inputs;
    private final List<WithdrawSettlementOutput> outputs;
    private final long feeSat;
    private final long changeSat;
    private final long totalRequestedSat;

    public BatchSettlementPlan(List<UtxoCandidate> inputs, List<WithdrawSettlementOutput> outputs,
                               long feeSat, long changeSat, long totalRequestedSat) {
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.feeSat = feeSat;
        this.changeSat = changeSat;
        this.totalRequestedSat = totalRequestedSat;
    }

    public List<UtxoCandidate> getInputs() {
        return Collections.unmodifiableList(inputs);
    }

    public List<WithdrawSettlementOutput> getOutputs() {
        return Collections.unmodifiableList(outputs);
    }

    public long getFeeSat() {
        return feeSat;
    }

    public long getChangeSat() {
        return changeSat;
    }

    public long getTotalRequestedSat() {
        return totalRequestedSat;
    }
}
