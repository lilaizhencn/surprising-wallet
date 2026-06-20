package com.surprising.wallet.testutil;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only helper for Tatum faucet calls.
 * The endpoint paths are configurable so the helper can track Tatum API changes without
 * changing production code.
 */
public final class TestTatumFaucetHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final Map<String, String> faucetPaths;

    public TestTatumFaucetHelper(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, defaultPaths());
    }

    public TestTatumFaucetHelper(String apiKey, String baseUrl, Map<String, String> faucetPaths) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.faucetPaths = new LinkedHashMap<>(Objects.requireNonNull(faucetPaths, "faucetPaths"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public HttpResponse<String> requestBitcoinTestnet(String address) throws IOException, InterruptedException {
        return request("btc-testnet", address);
    }

    public HttpResponse<String> requestEthereumTestnet(String address) throws IOException, InterruptedException {
        return request("eth-testnet", address);
    }

    public HttpResponse<String> requestPolygonTestnet(String address) throws IOException, InterruptedException {
        return request("polygon-testnet", address);
    }

    public HttpResponse<String> requestBscTestnet(String address) throws IOException, InterruptedException {
        return request("bsc-testnet", address);
    }

    private HttpResponse<String> request(String key, String address) throws IOException, InterruptedException {
        String path = faucetPaths.get(key);
        if (path == null) {
            throw new IllegalArgumentException("No faucet path configured for " + key);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("address", address);
        String payload = MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + path))
                .timeout(Duration.ofSeconds(30))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> defaultPaths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("btc-testnet", "/v3/blockchain/faucet/btc");
        paths.put("eth-testnet", "/v3/blockchain/faucet/eth");
        paths.put("polygon-testnet", "/v3/blockchain/faucet/polygon");
        paths.put("bsc-testnet", "/v3/blockchain/faucet/bsc");
        return paths;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
