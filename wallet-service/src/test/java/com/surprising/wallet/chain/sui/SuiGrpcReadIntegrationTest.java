package com.surprising.wallet.chain.sui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code SuiGrpcReadIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class SuiGrpcReadIntegrationTest {
    /**
     * 验证 {@code readsOfficialTestnetThroughGrpc} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void readsOfficialTestnetThroughGrpc() {
        Assumptions.assumeTrue(Boolean.getBoolean("sui.grpc.live.enabled"),
                "set -Dsui.grpc.live.enabled=true to validate the Sui gRPC endpoint");
        String endpoint = System.getenv().getOrDefault(
                "SUI_GRPC_ENDPOINT", "fullnode.testnet.sui.io:443");
        SuiRpcClient rpc = new SuiRpcClient(new ObjectMapper(), endpoint);

        assertTrue(rpc.latestCheckpoint() > 0L);
        assertTrue(rpc.referenceGasPrice() > 0L);
        assertTrue(rpc.balance("0x0", SuiRpcClient.SUI_COIN_TYPE).compareTo(BigDecimal.ZERO) >= 0);
    }
}
