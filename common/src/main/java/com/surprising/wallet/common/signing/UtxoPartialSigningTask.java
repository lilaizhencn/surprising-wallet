package com.surprising.wallet.common.signing;

import java.util.Objects;

/** The second signer receives immutable intent and the first signature together. */
public record UtxoPartialSigningTask(UtxoSigningTask task, UtxoPartialSignature signature) {
    public UtxoPartialSigningTask {
        Objects.requireNonNull(task, "signing task is required");
        Objects.requireNonNull(signature, "partial signature is required");
    }
}
