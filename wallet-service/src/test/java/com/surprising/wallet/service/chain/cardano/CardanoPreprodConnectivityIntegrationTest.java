package com.surprising.wallet.service.chain.cardano;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoPreprodConnectivityIntegrationTest {
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

    private static String envOrProperty(String env, String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null ? "" : value.trim();
    }
}
