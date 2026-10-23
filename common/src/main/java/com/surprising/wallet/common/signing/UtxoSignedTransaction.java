package com.surprising.wallet.common.signing;

import java.util.Objects;

/** Public signed transaction facts; monetary values are integer atomic units. */
public record UtxoSignedTransaction(
        String transactionHex, String txId, long feeAtomic, long weight, long vBytes,
        UtxoPartialSignature.Estimates estimates) {
    public UtxoSignedTransaction {
        Objects.requireNonNull(transactionHex, "signed transaction is required");
        Objects.requireNonNull(txId, "signed transaction hash is required");
        Objects.requireNonNull(estimates, "fee estimates are required");
    }
}
