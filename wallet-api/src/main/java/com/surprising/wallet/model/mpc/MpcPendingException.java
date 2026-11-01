package com.surprising.wallet.model.mpc;

import java.util.UUID;

/** A durable operation exists; the caller must reconcile it rather than changing the accepted intent. */
public final class MpcPendingException extends RuntimeException {
    private final UUID reference;
    private final String state;
    public MpcPendingException(UUID reference, String state) {
        super("signing operation is not ready; retry the same request");
        this.reference = reference;
        this.state = state;
    }
    public UUID reference() { return reference; }
    public String state() { return state; }
}
