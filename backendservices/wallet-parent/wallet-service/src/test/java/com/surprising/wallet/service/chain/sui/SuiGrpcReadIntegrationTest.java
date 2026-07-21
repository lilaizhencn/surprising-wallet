package com.surprising.wallet.service.chain.sui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiGrpcReadIntegrationTest {
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
