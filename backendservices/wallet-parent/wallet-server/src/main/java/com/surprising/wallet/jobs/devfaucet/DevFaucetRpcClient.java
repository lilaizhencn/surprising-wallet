package com.surprising.wallet.jobs.devfaucet;

interface DevFaucetRpcClient {
    String send(DevFaucetFunding funding);

    final class RejectedException extends RuntimeException {
        RejectedException(String message) { super(message); }
        RejectedException(String message, Throwable cause) { super(message, cause); }
    }

    final class AmbiguousException extends RuntimeException {
        AmbiguousException(String message, Throwable cause) { super(message, cause); }
    }
}
