package com.surprising.wallet.chain.hypercore;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainProfileRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HyperCore 链 API 客户端，封装对 HyperCore 交易所后端 REST API 的 HTTP 调用。
 *
 * <p>HyperCore 是一个中心化交易所（CEX）模式的链，通过后端 API 提供
 * spotClearinghouseState（账户状态查询）和 exchange（交易提交）等接口。</p>
 *
 * <p>所有请求均为 POST JSON 格式，支持多节点故障转移。</p>
 */
@Component
public class HyperCoreApiClient {

    /** 链标识常量 */
    static final String CHAIN = "HYPERCORE";
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ChainProfileRepository profiles;
    private final ChainRpcNodeService rpcNodeService;

    public HyperCoreApiClient(ObjectMapper objectMapper, ChainProfileRepository profiles,
                            ChainRpcNodeService rpcNodeService) {
        this.objectMapper = objectMapper;
        this.profiles = profiles;
        this.rpcNodeService = rpcNodeService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
    public JsonNode postInfo(ObjectNode body) {
        return post("info", "/info", body);
    }
    public JsonNode postExchange(ObjectNode body) {
        return post("exchange", "/exchange", body);
    }
    private JsonNode post(String purpose, String path, ObjectNode body) {
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            String network = profiles.findAccountByChain(CHAIN)
                    .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                    .getNetwork();
            return rpcNodeService.withFailover(CHAIN, network, purpose,
                    node -> execute(path, requestBody, node.getRpcUrl(), node));
        } catch (JacksonException e) {
            throw new IllegalStateException("HyperCore request serialization failed", e);
        }
    }
    private JsonNode execute(String path, String requestBody, String baseUrl, ChainRpcNode node) {
        String url = endpoint(baseUrl, path);
        try {
            for (int attempt = 1; attempt <= 4; attempt++) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("content-type", "application/json")
                        .header("accept", "application/json")
                        .header("user-agent", "surprising-wallet/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                rpcNodeService.applyAuthHeaders(builder, node);
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if ((response.statusCode() == 429 || response.statusCode() / 100 == 5) && attempt < 4) {
                    Thread.sleep(attempt * 1_000L);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("HyperCore HTTP " + response.statusCode()
                            + ": " + abbreviate(response.body()));
                }
                return objectMapper.readTree(response.body());
            }
            throw new IllegalStateException("HyperCore retry loop exhausted for " + path);
        } catch (IOException e) {
            throw new IllegalStateException("HyperCore HTTP request failed for " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HyperCore HTTP request interrupted for " + path, e);
        }
    }
    private static String endpoint(String baseUrl, String path) {
        String value = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (value.endsWith(path)) {
            return value;
        }
        if (value.endsWith("/info") || value.endsWith("/exchange")) {
            value = value.substring(0, value.lastIndexOf('/'));
        }
        return value + path;
    }
    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
