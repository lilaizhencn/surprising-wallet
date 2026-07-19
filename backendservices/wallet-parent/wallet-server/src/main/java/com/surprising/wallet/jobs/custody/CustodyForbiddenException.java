package com.surprising.wallet.jobs.custody;

public class CustodyForbiddenException extends RuntimeException {
    public CustodyForbiddenException(String message) {
        super(message);
    }
}
