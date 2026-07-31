package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.tron.trident.proto.Chain;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TronNileConnectivityIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TronNileConnectivityIntegrationTest {
    /**
     * 验证 {@code nileNode_shouldReturnLatestBlock} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void nileNode_shouldReturnLatestBlock() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("tron.live.enabled"),
                "set -Dtron.live.enabled=true to call Nile gRPC");
        String fullNode = System.getProperty("tron.fullnode", "grpc.nile.trongrid.io:50051");
        String solidityNode = System.getProperty("tron.soliditynode", "grpc.nile.trongrid.io:50061");
        String apiKey = System.getProperty("tron.apiKey", "");
        try (TronTridentClient client = new TronTridentClient(fullNode, solidityNode, apiKey)) {
            Chain.Block block = client.getNowBlock();
            assertTrue(block.getBlockHeader().getRawData().getNumber() > 0);
        }
    }
}
