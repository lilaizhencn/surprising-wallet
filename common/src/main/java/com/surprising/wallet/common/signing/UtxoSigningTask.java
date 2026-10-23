package com.surprising.wallet.common.signing;

import java.util.Objects;

/** Assigned business identity plus the persisted intent; no database lifecycle state. */
public record UtxoSigningTask(int transactionId, UtxoSigningRequest request) {
    public UtxoSigningTask {
        if (transactionId <= 0) throw new IllegalArgumentException("signing transaction id is required");
        Objects.requireNonNull(request, "signing request is required");
    }
}
