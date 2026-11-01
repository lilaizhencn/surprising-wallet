package com.surprising.wallet.model.mpc;

import com.surprising.wallet.common.signing.MpcClient;
import java.util.Map;
import java.util.UUID;

public record MpcSignAttempt(UUID requestId, MpcClient.Request request, com.surprising.wallet.common.approval.SigningAuthorization.Proof authorization,
                             com.surprising.wallet.common.approval.AutomaticOperation automaticOperation,State state,
                             String signatureCiphertext, Map<String, MpcObservation> nodeResults,
                             String replayActor, String replayReason) {
    public enum State { PENDING, RUNNING, COMPLETED, INDETERMINATE }
    public MpcSignAttempt { nodeResults = Map.copyOf(nodeResults); }
}
