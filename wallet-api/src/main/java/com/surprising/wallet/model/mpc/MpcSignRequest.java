package com.surprising.wallet.model.mpc;

import com.surprising.wallet.common.signing.MpcSignCommand;

import java.util.UUID;

public record MpcSignRequest(UUID id, MpcSignCommand command, State state, UUID currentAttemptId) {
    public enum State { PENDING, SIGNING, COMPLETED, INDETERMINATE, CANCELLED }
}
