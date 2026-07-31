package com.surprising.wallet.chain.near;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code NearTestnetConnectivityIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class NearTestnetConnectivityIntegrationTest {
    /**
     * 验证 {@code readsFinalBlockFromOfficialTestnetRpc} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void readsFinalBlockFromOfficialTestnetRpc() {
        Assumptions.assumeTrue(Boolean.getBoolean("near.testnet.enabled"),
                "set -Dnear.testnet.enabled=true to run NEAR testnet connectivity");
        String rpcUrl = System.getProperty("near.testnet.rpc",
                "https://rpc.testnet.near.org");

        long height = new NearRpcClient(new ObjectMapper(), rpcUrl).latestFinalBlockHeight();

        assertTrue(height > 0, "NEAR testnet final block height should be positive");
    }
}
