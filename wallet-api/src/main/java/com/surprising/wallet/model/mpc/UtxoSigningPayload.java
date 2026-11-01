package com.surprising.wallet.model.mpc;

import com.surprising.wallet.common.signing.UtxoSigningTask;
import com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec;
import java.util.List;
import java.util.UUID;

/** Complete immutable batch, public input keys and exact unsigned transaction, shared by every input slot. */
public record UtxoSigningPayload(UtxoSigningTask task, MpcUtxoTransactionCodec.Unsigned unsigned, List<UUID> keyIds) {
    public UtxoSigningPayload { keyIds = List.copyOf(keyIds); }
}
