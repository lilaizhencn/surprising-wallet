package com.surprising.wallet.model.mpc;

import com.surprising.wallet.common.signing.MpcClient;
import java.util.Map;
import java.util.UUID;

public record MpcSignAttempt(UUID requestId, MpcClient.Request request, State state,
                             String signatureCiphertext, Map<String, MpcObservation> nodeResults,
                             String replayActor, String replayReason) {
    public enum State { PENDING, RUNNING, COMPLETED, INDETERMINATE }
    public MpcSignAttempt { nodeResults = Map.copyOf(nodeResults); }
}
