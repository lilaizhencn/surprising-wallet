package com.surprising.wallet.model.mpc;

import java.util.UUID;

public record MpcSignRequest(UUID id, MpcSignCommand command, State state, UUID currentAttemptId) {
    public enum State { PENDING, SIGNING, COMPLETED, INDETERMINATE }
}
