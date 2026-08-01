package com.surprising.wallet.chain.cardano;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code CardanoPreprodConnectivityIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CardanoPreprodConnectivityIntegrationTest {
    /**
     * 验证 {@code readsLatestBlockFromBlockfrostPreprod} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void readsLatestBlockFromBlockfrostPreprod() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cardano.preprod.enabled"),
                "set -Dcardano.preprod.enabled=true and BLOCKFROST_PREPROD_PROJECT_ID to run Cardano preprod connectivity");
        String projectId = envOrProperty("BLOCKFROST_PREPROD_PROJECT_ID", "cardano.preprod.projectId");
        Assumptions.assumeFalse(projectId.isBlank(), "missing BLOCKFROST_PREPROD_PROJECT_ID");
        String apiUrl = System.getProperty("cardano.preprod.api",
                "https://cardano-preprod.blockfrost.io/api/v0");

        var result = new BFBackendService(apiUrl, projectId).getBlockService().getLatestBlock();

        assertTrue(result.isSuccessful(), "Blockfrost preprod latest block call should succeed");
        assertTrue(result.getValue().getHeight() > 0, "Cardano preprod latest block height should be positive");
    }

    /**
     * 验证 {@code envOrProperty} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String envOrProperty(String env, String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null ? "" : value.trim();
    }
}
