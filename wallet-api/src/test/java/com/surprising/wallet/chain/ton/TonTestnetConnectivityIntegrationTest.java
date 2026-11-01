package com.surprising.wallet.chain.ton;

import com.surprising.wallet.common.chain.ChainRpcNode;
import static com.surprising.wallet.testutil.RpcTestSupport.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TonTestnetConnectivityIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TonTestnetConnectivityIntegrationTest {

    /**
     * 验证 {@code tonCenterTestnetIsReachable} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tonCenterTestnetIsReachable() {
        Assumptions.assumeTrue(Boolean.getBoolean("ton.testnet.enabled"),
                "set -Dton.testnet.enabled=true for TON testnet connectivity validation");


        TonCenterClient rpc = new TonCenterClient(new ObjectMapper(),
                profiles("TON"),
                nodes(ChainRpcNode.builder().chain("TON")
                        .network("test")
                        .rpcUrl(env("TON_RPC_URL", "https://testnet.toncenter.com/api/v2"))
                        .authType("api_key")
                        .authHeaderName("X-API-Key")
                        .apiKey(env("TONCENTER_API_KEY", "")).build()));
        JsonNode info = rpc.masterchainInfo();
        assertTrue(info.path("last").path("seqno").asLong() > 0);
        System.out.println("TON_TESTNET_MASTERCHAIN_SEQNO=" + info.path("last").path("seqno").asLong());
    }

    /**
     * 验证 {@code env} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
