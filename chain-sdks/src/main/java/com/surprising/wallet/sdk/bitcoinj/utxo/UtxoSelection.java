package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.Collections;
import java.util.List;

/**
 * Result of a BTC UTXO selection round.
 */
public final class UtxoSelection {
    private final List<UtxoCandidate> selected;
    private final long targetSat;
    private final long inputSat;
    private final long feeSat;
    private final long changeSat;
    private final boolean exactMatch;

    public UtxoSelection(List<UtxoCandidate> selected, long targetSat, long inputSat,
                         long feeSat, long changeSat, boolean exactMatch) {
        this.selected = List.copyOf(selected);
        this.targetSat = targetSat;
        this.inputSat = inputSat;
        this.feeSat = feeSat;
        this.changeSat = changeSat;
        this.exactMatch = exactMatch;
    }

    public List<UtxoCandidate> getSelected() {
        return Collections.unmodifiableList(selected);
    }

    public long getTargetSat() {
        return targetSat;
    }

    public long getInputSat() {
        return inputSat;
    }

    public long getFeeSat() {
        return feeSat;
    }

    public long getChangeSat() {
        return changeSat;
    }

    public boolean isExactMatch() {
        return exactMatch;
    }

    public long getInputCount() {
        return selected.size();
    }
}
