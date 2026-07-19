package com.surprising.wallet.jobs.custody;

public class CustodyUnauthorizedException extends RuntimeException {
    public CustodyUnauthorizedException(String message) {
        super(message);
    }
}
