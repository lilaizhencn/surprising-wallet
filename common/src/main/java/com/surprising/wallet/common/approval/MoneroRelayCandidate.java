package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.signing.MpcClient;
import java.util.UUID;

/** A relay refers to an original independently authorized payment session; it never creates a new payment. */
public record MoneroRelayCandidate(UUID signingRequestId,UUID signSessionId,MpcClient.Request execution) {
    public MoneroRelayCandidate {
        if(signingRequestId==null || signSessionId==null || execution==null || execution.operation()!=MpcClient.Operation.MONERO_RELAY
                || execution.algorithm()!=MpcClient.Algorithm.MONERO_NATIVE_2_OF_3 || execution.online().size()!=1)
            throw new IllegalArgumentException("original native Monero relay identity is required");
    }
}
