package com.surprising.wallet.model.mpc;

import com.surprising.wallet.common.signing.MpcClient;
import java.util.Map;

public record MpcKeyEpoch(MpcClient.Request request, State state, Map<String, MpcObservation> nodeResults,
                          String refreshActor, String refreshReason) {
    public enum State { PREPARING, ACTIVE, REFRESHING, FAILED, RETIRED }
    public MpcKeyEpoch { nodeResults = Map.copyOf(nodeResults); }
}
