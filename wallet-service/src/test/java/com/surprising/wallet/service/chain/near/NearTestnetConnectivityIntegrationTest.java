package com.surprising.wallet.service.chain.near;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NearTestnetConnectivityIntegrationTest {
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
