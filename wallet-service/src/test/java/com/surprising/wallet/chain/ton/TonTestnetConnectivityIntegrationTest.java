package com.surprising.wallet.chain.ton;

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
     * 保存 {@code FALLBACK_TEST_SEED}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final String FALLBACK_TEST_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * 验证 {@code tonCenterTestnetIsReachableAndFundingAddressCanBeDerived} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tonCenterTestnetIsReachableAndFundingAddressCanBeDerived() {
        Assumptions.assumeTrue(Boolean.getBoolean("ton.testnet.enabled"),
                "set -Dton.testnet.enabled=true for TON testnet connectivity validation");

        String seed = env("SW_ED25519_SEED", FALLBACK_TEST_SEED);
        TonKeyService keys = new TonKeyService(seed);
        String address = keys.wallet(1_100_001L).getAddress().toString(true, true, false, true);
        assertFalse(address.isBlank());
        System.out.println("TON_TESTNET_FUNDING_ADDRESS=" + address);

        TonCenterClient rpc = new TonCenterClient(new ObjectMapper(),
                env("TON_RPC_URL", "https://testnet.toncenter.com/api/v2"),
                env("TONCENTER_API_KEY", ""));
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
