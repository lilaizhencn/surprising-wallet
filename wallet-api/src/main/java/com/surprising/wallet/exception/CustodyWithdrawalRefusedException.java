package com.surprising.wallet.exception;

/** The permanent submission receipt refuses every future create with the same identity. */
public final class CustodyWithdrawalRefusedException extends IllegalStateException {
    private final String idempotencyKey;
    private final String requestHash;

    public CustodyWithdrawalRefusedException(String idempotencyKey, String requestHash) {
        super("withdrawal submission was durably refused");
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    public String idempotencyKey() { return idempotencyKey; }
    public String requestHash() { return requestHash; }
}
