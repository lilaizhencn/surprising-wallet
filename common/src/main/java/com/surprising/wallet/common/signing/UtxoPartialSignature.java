package com.surprising.wallet.common.signing;

import java.util.List;
import java.util.Objects;

/** First signature and the public script material needed by the second signer. */
public record UtxoPartialSignature(
        String transactionHex, ScriptType scriptType, List<String> inputScripts,
        long feeAtomic, Estimates estimates) {
    public UtxoPartialSignature {
        Objects.requireNonNull(transactionHex, "partial transaction is required");
        Objects.requireNonNull(scriptType, "script type is required");
        inputScripts = List.copyOf(inputScripts);
        Objects.requireNonNull(estimates, "fee estimates are required");
    }

    public enum ScriptType { P2WSH, P2SH, BCH_P2SH }
    public record Estimates(long bytes, long vBytes, long weight) { }
}
