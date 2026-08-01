package com.surprising.wallet.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TestTatumFaucetHelperTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TestTatumFaucetHelperTest {
    /**
     * 验证 {@code helperShouldSendApiKeyAndJsonBody} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void helperShouldSendApiKeyAndJsonBody() throws Exception {
        CapturedRequest captured = new CapturedRequest();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/blockchain/faucet/btc", exchange -> handle(exchange, captured));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            TestTatumFaucetHelper helper = new TestTatumFaucetHelper("test-key", baseUrl,
                    Map.of("btc-testnet", "/v3/blockchain/faucet/btc"), 1, Duration.ofMillis(1));
            helper.requestBitcoinTestnet("tb1qexampleaddress");

            assertEquals("POST", captured.method);
            assertEquals("test-key", captured.apiKey);
            assertTrue(captured.body.contains("tb1qexampleaddress"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 验证 {@code helperShouldRetryRateLimitedRequest} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void helperShouldRetryRateLimitedRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/blockchain/faucet/btc", exchange -> {
            int current = calls.incrementAndGet();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(current == 1 ? 429 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            TestTatumFaucetHelper helper = new TestTatumFaucetHelper("test-key", baseUrl,
                    Map.of("btc-testnet", "/v3/blockchain/faucet/btc"), 2, Duration.ofMillis(1));

            assertEquals(200, helper.requestBitcoinTestnet("tb1qexampleaddress").statusCode());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 验证 {@code handle} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void handle(HttpExchange exchange, CapturedRequest captured) throws IOException {
        captured.method = exchange.getRequestMethod();
        captured.apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
        try (InputStream inputStream = exchange.getRequestBody()) {
            captured.body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    /**
     * 测试辅助类 {@code CapturedRequest}，为相关测试提供隔离环境或共享数据。
     */
    private static final class CapturedRequest {
        /**
         * 保存 {@code method}，用于承载当前测试夹具的配置或运行数据。
         */
        private String method;
        /**
         * 保存 {@code apiKey}，用于测试签名、认证或密钥相关逻辑。
         */
        private String apiKey;
        /**
         * 保存 {@code body}，用于承载当前测试夹具的配置或运行数据。
         */
        private String body;
    }
}
