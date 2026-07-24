package com.surprising.wallet.chain.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * TON API 索引器客户端，用于查询 Jetton Wallet 地址等高级链上数据。
 *
 * <p>通过 TON API (tonapi.io) 的 REST API 查询 Jetton Wallet 地址等信息。
 * 使用 Bearer Token 认证，支持生产环境（通过 {@link ChainRpcNodeService} 故障转移）
 * 和测试环境（固定 baseUrl + apiKey）两种模式。</p>
 *
 * <p>与 {@link TonCenterClient} 互补：TonCenterClient 处理交易广播和基础查询，
 * 本客户端处理需要索引器的查询（如 Jetton wallet 解析）。</p>
 *
 * @see TonCenterClient
 */
@Component
public
class TonApiClient {

    /** 链标识常量 */
    private static final String CHAIN = "TON";

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端 */
    private final HttpClient httpClient;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;

    /** 固定 base URL（测试用） */
    private final String fixedBaseUrl;

    /** 固定 API Key（测试用） */
    private final String fixedApiKey;

    @Autowired
    public TonApiClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this.objectMapper = new ObjectMapper();
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.fixedBaseUrl = "";
        this.fixedApiKey = "";
        this.httpClient = buildHttpClient();
    }

    TonApiClient(ObjectMapper objectMapper, String baseUrl, String apiKey) {
        this.objectMapper = objectMapper;
        this.repository = null;
        this.rpcNodeService = null;
        this.fixedBaseUrl = trim(baseUrl);
        this.fixedApiKey = apiKey == null ? "" : apiKey;
        this.httpClient = buildHttpClient();
    }

    /**
     * 解析指定所有者的 Jetton Wallet 地址。
     *
     * @param ownerAddress  所有者地址
     * @param jettonMaster  Jetton Master 合约地址
     * @return Jetton Wallet 地址
     */
    public String resolveJettonWallet(String ownerAddress, String jettonMaster) {
        JsonNode result = get("/v2/accounts/" + encode(ownerAddress)
                + "/jettons/" + encode(jettonMaster));
        String address = result.path("wallet_address").path("address").asText();
        if (address.isBlank()) {
            throw new IllegalStateException("TON API did not return a jetton wallet address");
        }
        return address;
    }

    /** 执行 GET 请求 */
    private JsonNode get(String path) {
        if (!fixedBaseUrl.isBlank()) {
            return execute(fixedBaseUrl + path, null, fixedApiKey);
        }
        String network = repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                .getNetwork();
        return rpcNodeService.withFailover(CHAIN, network, "indexer",
                node -> execute(trim(node.getRpcUrl()) + path, node, ""));
    }

    /** 执行 HTTP 请求并解析结果 */
    private JsonNode execute(String url, ChainRpcNode node, String apiKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("accept", "application/json")
                    .header("user-agent", "surprising-wallet/1.0")
                    .GET();
            if (node != null) {
                rpcNodeService.applyAuthHeaders(builder, node);
            } else if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("TON API HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("TON API IO failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TON API interrupted", e);
        }
    }

    /** URL 编码 */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 去除 URL 末尾斜杠 */
    private static String trim(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    /** 构建 TLS 1.2 HTTP 客户端 */
    private static HttpClient buildHttpClient() {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, null, null);
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(context)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("unable to initialize TON API TLS client", e);
        }
    }
}
