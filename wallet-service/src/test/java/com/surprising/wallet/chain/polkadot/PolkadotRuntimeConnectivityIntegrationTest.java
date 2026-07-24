package com.surprising.wallet.chain.polkadot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PolkadotRuntimeConnectivityIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void runtimeServiceReadsWestendFinalizedHeight() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("polkadot.runtime.live.enabled"),
                "set -Dpolkadot.runtime.live.enabled=true with a running runtime service");
        String rpcUrl = System.getProperty("polkadot.westend.rpc",
                "wss://westend-rpc.polkadot.io");

        assertFinalizedHeight(rpcUrl);
    }

    @Test
    void runtimeServiceReadsWestendAssetHubFinalizedHeight() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("polkadot.runtime.live.enabled"),
                "set -Dpolkadot.runtime.live.enabled=true with a running runtime service");
        String rpcUrl = System.getProperty("polkadot.assethub.westend.rpc",
                "wss://westend-asset-hub-rpc.polkadot.io");

        assertFinalizedHeight(rpcUrl);
    }

    private static void assertFinalizedHeight(String rpcUrl) throws Exception {
        String serviceUrl = System.getProperty("polkadot.runtime.url",
                "http://127.0.0.1:8787");
        String apiKey = envOrProperty("POLKADOT_RUNTIME_API_KEY", "polkadot.runtime.apiKey");
        ObjectNode body = MAPPER.createObjectNode();
        body.put("rpcUrl", rpcUrl);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(serviceUrl + "/v1/polkadot/latest-finalized"))
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        ObjectNode json = (ObjectNode) MAPPER.readTree(response.body());

        assertTrue(response.statusCode() / 100 == 2, response.body());
        assertTrue(json.path("ok").asBoolean(false), response.body());
        assertTrue(json.path("result").path("height").asLong(0L) > 0, response.body());
    }

    private static String envOrProperty(String env, String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null ? "" : value.trim();
    }
}
