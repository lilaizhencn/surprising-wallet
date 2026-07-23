package com.surprising.wallet.service.chain.near;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearRpcClientTest {
    @Test
    void recognizesMissingAccountErrors() {
        assertTrue(NearRpcClient.isMissingAccountError(
                "NEAR RPC query failed: {\"name\":\"UNKNOWN_ACCOUNT\"}"));
        assertTrue(NearRpcClient.isMissingAccountError(
                "AccountDoesNotExist { account_id: example.testnet }"));
        assertTrue(NearRpcClient.isMissingAccountError(
                "does not exist while viewing"));
    }

    @Test
    void leavesOtherRpcErrorsRetryable() {
        assertFalse(NearRpcClient.isMissingAccountError("NEAR HTTP 429: rate limited"));
        assertFalse(NearRpcClient.isMissingAccountError("Invalid nonce"));
    }

    @Test
    void recognizesMissingBlockHeights() {
        assertTrue(NearRpcClient.isUnknownBlockError(
                "NEAR HTTP 422: {\"name\":\"UNKNOWN_BLOCK\"}"));
        assertTrue(NearRpcClient.isUnknownBlockError(
                "DB Not Found Error: BLOCK HEIGHT: 256859526 Cause: Unknown"));
        assertFalse(NearRpcClient.isUnknownBlockError("NEAR HTTP 429: rate limited"));
    }
}
