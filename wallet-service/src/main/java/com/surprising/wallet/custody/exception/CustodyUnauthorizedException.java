package com.surprising.wallet.custody.exception;

public class CustodyUnauthorizedException extends RuntimeException {
    public CustodyUnauthorizedException(String message) {
        super(message);
    }
}
