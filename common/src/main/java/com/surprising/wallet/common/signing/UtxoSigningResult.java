package com.surprising.wallet.common.signing;

import java.util.Objects;
import java.util.UUID;

/** Signers return a fenced result; intent and RBF history remain owned by the API database. */
public record UtxoSigningResult(
        int transactionId, String chain, UUID tenantId, UUID requestId,
        UtxoSignedTransaction transaction, String error) {
    public UtxoSigningResult {
        if (transactionId <= 0) throw new IllegalArgumentException("signing transaction id is required");
        Objects.requireNonNull(chain, "result chain is required");
        Objects.requireNonNull(tenantId, "result tenant is required");
        Objects.requireNonNull(requestId, "result request id is required");
        if ((transaction == null) == (error == null)) {
            throw new IllegalArgumentException("signing result must contain a transaction or an error");
        }
    }

    public boolean successful() { return transaction != null; }

    public static UtxoSigningResult success(UtxoSigningTask task, UtxoSignedTransaction transaction) {
        var request = task.request();
        return new UtxoSigningResult(task.transactionId(), request.asset().chain(), request.tenantId(),
                request.requestId(), Objects.requireNonNull(transaction), null);
    }

    public static UtxoSigningResult failure(UtxoSigningTask task, String error) {
        var request = task.request();
        return new UtxoSigningResult(task.transactionId(), request.asset().chain(), request.tenantId(),
                request.requestId(), null, error == null || error.isBlank() ? "signing failed" : error);
    }
}
