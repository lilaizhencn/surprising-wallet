package com.surprising.wallet.chain.near;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code NearRpcClientTest} 覆盖的业务流程、边界条件和异常行为。
 */
class NearRpcClientTest {
    /**
     * 验证 {@code recognizesMissingAccountErrors} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void recognizesMissingAccountErrors() {
        assertTrue(NearRpcClient.isMissingAccountError(
                "NEAR RPC query failed: {\"name\":\"UNKNOWN_ACCOUNT\"}"));
        assertTrue(NearRpcClient.isMissingAccountError(
                "AccountDoesNotExist { account_id: example.testnet }"));
        assertTrue(NearRpcClient.isMissingAccountError(
                "does not exist while viewing"));
    }

    /**
     * 验证 {@code leavesOtherRpcErrorsRetryable} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void leavesOtherRpcErrorsRetryable() {
        assertFalse(NearRpcClient.isMissingAccountError("NEAR HTTP 429: rate limited"));
        assertFalse(NearRpcClient.isMissingAccountError("Invalid nonce"));
    }

    /**
     * 验证 {@code recognizesMissingBlockHeights} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void recognizesMissingBlockHeights() {
        assertTrue(NearRpcClient.isUnknownBlockError(
                "NEAR HTTP 422: {\"name\":\"UNKNOWN_BLOCK\"}"));
        assertTrue(NearRpcClient.isUnknownBlockError(
                "DB Not Found Error: BLOCK HEIGHT: 256859526 Cause: Unknown"));
        assertFalse(NearRpcClient.isUnknownBlockError("NEAR HTTP 429: rate limited"));
    }
}
