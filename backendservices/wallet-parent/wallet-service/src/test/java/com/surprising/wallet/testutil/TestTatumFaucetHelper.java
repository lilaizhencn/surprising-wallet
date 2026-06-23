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
import java.util.logging.Logger;

/**
 * Test-only helper for Tatum faucet calls. This class deliberately lives under
 * {@code src/test} and is not a Spring bean, so faucet access cannot leak into
 * production wallet services.
 *
 * The endpoint paths are configurable so the helper can track Tatum API changes without
 * changing production code.
 */
public final class TestTatumFaucetHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOGGER = Logger.getLogger(TestTatumFaucetHelper.class.getName());

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final Map<String, String> faucetPaths;
    private final int maxAttempts;
    private final Duration retryDelay;

    public TestTatumFaucetHelper(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, defaultPaths(), 3, Duration.ofSeconds(2));
    }

    public TestTatumFaucetHelper(String apiKey, String baseUrl, Map<String, String> faucetPaths) {
        this(apiKey, baseUrl, faucetPaths, 3, Duration.ofSeconds(2));
    }

    public TestTatumFaucetHelper(String apiKey, String baseUrl, Map<String, String> faucetPaths,
                                 int maxAttempts, Duration retryDelay) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.faucetPaths = new LinkedHashMap<>(Objects.requireNonNull(faucetPaths, "faucetPaths"));
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static TestTatumFaucetHelper fromEnvironment(String baseUrl) {
        String apiKey = System.getenv("TATUM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TATUM_API_KEY is required for live faucet tests");
        }
        return new TestTatumFaucetHelper(apiKey, baseUrl);
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
        HttpResponse<String> lastResponse = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            lastResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logResult(key, address, attempt, lastResponse);
            if (!shouldRetry(lastResponse.statusCode()) || attempt == maxAttempts) {
                return lastResponse;
            }
            Thread.sleep(retryDelay(lastResponse).toMillis());
        }
        return lastResponse;
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

    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private Duration retryDelay(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(TestTatumFaucetHelper::parseRetryAfterSeconds)
                .map(Duration::ofSeconds)
                .orElse(retryDelay);
    }

    private static java.util.Optional<Long> parseRetryAfterSeconds(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static void logResult(String faucetKey, String address, int attempt, HttpResponse<String> response) {
        LOGGER.info(() -> "tatum faucet result key=" + faucetKey
                + ", address=" + address
                + ", attempt=" + attempt
                + ", status=" + response.statusCode()
                + ", body=" + response.body());
    }
}
