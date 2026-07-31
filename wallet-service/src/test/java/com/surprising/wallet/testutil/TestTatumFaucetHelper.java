package com.surprising.wallet.testutil;

import tools.jackson.databind.ObjectMapper;

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
 * 测试辅助类 {@code TestTatumFaucetHelper}，为相关测试提供隔离环境或共享数据。
 */
public final class TestTatumFaucetHelper {
    /**
     * 保存 {@code MAPPER}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * 保存 {@code LOGGER}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final Logger LOGGER = Logger.getLogger(TestTatumFaucetHelper.class.getName());

    /**
     * 保存 {@code httpClient}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private final HttpClient httpClient;
    /**
     * 保存 {@code apiKey}，用于测试签名、认证或密钥相关逻辑。
     */
    private final String apiKey;
    /**
     * 保存 {@code baseUrl}，用于承载当前测试夹具的配置或运行数据。
     */
    private final String baseUrl;
    /**
     * 保存 {@code faucetPaths}，用于承载当前测试夹具的配置或运行数据。
     */
    private final Map<String, String> faucetPaths;
    /**
     * 保存 {@code maxAttempts}，记录测试开关、处理状态、确认结果或重试信息。
     */
    private final int maxAttempts;
    /**
     * 保存 {@code retryDelay}，记录测试开关、处理状态、确认结果或重试信息。
     */
    private final Duration retryDelay;

    /**
     * 验证 {@code TestTatumFaucetHelper} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    public TestTatumFaucetHelper(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, defaultPaths(), 3, Duration.ofSeconds(2));
    }

    /**
     * 验证 {@code TestTatumFaucetHelper} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    public TestTatumFaucetHelper(String apiKey, String baseUrl, Map<String, String> faucetPaths) {
        this(apiKey, baseUrl, faucetPaths, 3, Duration.ofSeconds(2));
    }

    /**
     * 验证 {@code TestTatumFaucetHelper} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code fromEnvironment} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    public static TestTatumFaucetHelper fromEnvironment(String baseUrl) {
        String apiKey = System.getenv("TATUM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TATUM_API_KEY is required for live faucet tests");
        }
        return new TestTatumFaucetHelper(apiKey, baseUrl);
    }

    /**
     * 验证 {@code requestBitcoinTestnet} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    public HttpResponse<String> requestBitcoinTestnet(String address) throws IOException, InterruptedException {
        return request("btc-testnet", address);
    }

    /**
     * 验证 {@code requestEthereumTestnet} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    public HttpResponse<String> requestEthereumTestnet(String address) throws IOException, InterruptedException {
        return request("eth-testnet", address);
    }

    /**
     * 验证 {@code requestPolygonTestnet} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    public HttpResponse<String> requestPolygonTestnet(String address) throws IOException, InterruptedException {
        return request("polygon-testnet", address);
    }

    /**
     * 验证 {@code requestBscTestnet} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    public HttpResponse<String> requestBscTestnet(String address) throws IOException, InterruptedException {
        return request("bsc-testnet", address);
    }

    /**
     * 验证 {@code request} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code defaultPaths} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Map<String, String> defaultPaths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("btc-testnet", "/v3/blockchain/faucet/btc");
        paths.put("eth-testnet", "/v3/blockchain/faucet/eth");
        paths.put("polygon-testnet", "/v3/blockchain/faucet/polygon");
        paths.put("bsc-testnet", "/v3/blockchain/faucet/bsc");
        return paths;
    }

    /**
     * 验证 {@code trimTrailingSlash} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 验证 {@code shouldRetry} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /**
     * 验证 {@code retryDelay} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private Duration retryDelay(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(TestTatumFaucetHelper::parseRetryAfterSeconds)
                .map(Duration::ofSeconds)
                .orElse(retryDelay);
    }

    /**
     * 验证 {@code parseRetryAfterSeconds} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static java.util.Optional<Long> parseRetryAfterSeconds(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    /**
     * 验证 {@code logResult} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void logResult(String faucetKey, String address, int attempt, HttpResponse<String> response) {
        LOGGER.info(() -> "tatum faucet result key=" + faucetKey
                + ", address=" + address
                + ", attempt=" + attempt
                + ", status=" + response.statusCode()
                + ", body=" + response.body());
    }
}
