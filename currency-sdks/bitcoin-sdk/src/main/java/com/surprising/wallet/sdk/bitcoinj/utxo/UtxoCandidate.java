package com.surprising.wallet.sdk.bitcoinj.utxo;

import java.util.Objects;

/**
 * Deterministic representation of a spendable BTC UTXO.
 */
public final class UtxoCandidate implements Comparable<UtxoCandidate> {
    private final String txId;
    private final int index;
    private final long satoshis;

    public UtxoCandidate(String txId, int index, long satoshis) {
        if (txId == null || txId.isBlank()) {
            throw new IllegalArgumentException("txId must not be blank");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        if (satoshis <= 0) {
            throw new IllegalArgumentException("satoshis must be positive");
        }
        this.txId = txId;
        this.index = index;
        this.satoshis = satoshis;
    }

    public String getTxId() {
        return txId;
    }

    public int getIndex() {
        return index;
    }

    public long getSatoshis() {
        return satoshis;
    }

    @Override
    public int compareTo(UtxoCandidate other) {
        int byValue = Long.compare(this.satoshis, other.satoshis);
        if (byValue != 0) {
            return byValue;
        }
        int byTx = this.txId.compareTo(other.txId);
        if (byTx != 0) {
            return byTx;
        }
        return Integer.compare(this.index, other.index);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof UtxoCandidate other)) {
            return false;
        }
        return index == other.index && satoshis == other.satoshis && txId.equals(other.txId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txId, index, satoshis);
    }

    @Override
    public String toString() {
        return txId + ":" + index + "@" + satoshis;
    }
}
