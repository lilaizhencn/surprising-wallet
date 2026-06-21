package com.surprising.wallet.service.chain.ltc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Live Litecoin testnet gate. It is intentionally disabled unless a
 * Litecoin Core-compatible RPC endpoint and funded testnet wallet are provided.
 */
class LitecoinLiveFlowIntegrationTest {
    @Test
    void liveLitecoinFlowRequiresFundedTestnetRpc() {
        Assumptions.assumeTrue(Boolean.getBoolean("ltc.live.enabled"),
                "blocked: set -Dltc.live.enabled=true with LTC_TESTNET_RPC_URL/user/password and funded testnet coins");
    }
}
