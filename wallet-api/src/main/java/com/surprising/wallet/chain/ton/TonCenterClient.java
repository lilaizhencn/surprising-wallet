package com.surprising.wallet.chain.ton;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import java.util.HexFormat;
import java.util.Optional;

/**
 * TON Center REST API 客户端，通过 HTTP 与 TON Center 节点交互。
 *
 * <p>封装了 TON Center 的常用 API（getMasterchainInfo、getWalletInformation、
 * getAddressBalance、getTransactions、sendBocReturnHash、runGetMethod 等），
 * 支持：
 * <ul>
 *   <li>节点故障转移（{@link ChainRpcNodeService#withFailover}）</li>
 *   <li>自动重试（最多 3 次，含 429 限流退避）</li>
 *   <li>无 API Key 时的速率限制（每请求间隔至少 1050ms）</li>
 *   <li>哈希对比（支持 Hex 和 Base64 编码）</li>
 * </ul>
 *
 * <p>所有 API 返回 JSON-RPC 风格的结果（{@code {"ok": true, "result": ...}}），
 * 该类自动提取 result 字段。
 *
 * @see TonApiClient
 */
@Component
public
class TonCenterClient {

    /** TON 链标识 */
    private static final String CHAIN = "TON";

    /** JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端 */
    private final HttpClient httpClient;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;

    /** 测试用固定 base URL（非空白时优先使用） */
    private final String fixedBaseUrl;

    /** 测试用固定 API Key（非空白时优先使用） */
    private final String fixedApiKey;

    /** 上次请求时间戳（用于无 API Key 时的速率限制） */
    private long lastRequestMillis;

    /**
     * 构造 {@code TonCenterClient}，初始化该组件运行所需的状态和依赖。
     */
    @Autowired
    public TonCenterClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null, null);
    }

    /**
     * 构造 {@code TonCenterClient}，初始化该组件运行所需的状态和依赖。
     */
    TonCenterClient(ObjectMapper objectMapper, String baseUrl, String apiKey) {
        this(objectMapper, null, null, baseUrl, apiKey);
    }

    /**
     * 构造 {@code TonCenterClient}，初始化该组件运行所需的状态和依赖。
     */
    private TonCenterClient(ObjectMapper objectMapper, ChainJdbcRepository repository,
                            ChainRpcNodeService rpcNodeService, String baseUrl, String apiKey) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.fixedBaseUrl = trim(baseUrl);
        this.fixedApiKey = apiKey == null ? "" : apiKey;
        this.httpClient = buildHttpClient();
    }
    /**
     * 获取主链最新信息（含 seqno）。
     *
     * @return masterchainInfo 结果 JSON
     */
    public JsonNode masterchainInfo() {
        return get("/getMasterchainInfo");
    }
    /**
     * 添加 {@code addressInformation} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public JsonNode addressInformation(String address) {
        return get("/getAddressInformation?address=" + encode(address));
    }
    /**
     * 执行 {@code walletInformation} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public JsonNode walletInformation(String address) {
        return get("/getWalletInformation?address=" + encode(address));
    }
    /**
     * 执行 {@code balance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public long balance(String address) {
        return get("/getAddressBalance?address=" + encode(address)).asLong();
    }
    /**
     * 获取钱包当前 seqno（用于交易序列号）。
     *
     * @param address TON 地址
     * @return seqno（0 表示未部署）
     */
    public long seqno(String address) {
        JsonNode wallet = get("/getWalletInformation?address=" + encode(address));
        return wallet.path("seqno").asLong(0);
    }
    /**
     * 执行 {@code transactions} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public JsonNode transactions(String address, int limit) {
        return get("/getTransactions?address=" + encode(address) + "&limit=" + limit + "&archival=true");
    }
    /**
     * 广播 BOC（Bag of Cells）消息到 TON 网络。
     *
     * @param boc BOC 字节数组
     * @return 消息哈希
     */
    public String sendBoc(byte[] boc) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("boc", java.util.Base64.getEncoder().encodeToString(boc));
        JsonNode result = post("/sendBocReturnHash", body);
        return result.path("hash").asText();
    }
    /**
     * 在地址的交易历史中查找匹配的外部消息。
     *
     * <p>通过比对消息哈希确认外部消息是否已被链上处理。
     *
     * @param address      发送方地址
     * @param messageHash  外部消息哈希
     * @param limit        扫描交易条数上限
     * @return 匹配的交易 JSON，未找到返回 empty
     */
    public Optional<JsonNode> findExternalMessageTransaction(String address, String messageHash, int limit) {
        JsonNode transactions = transactions(address, limit);
        if (!transactions.isArray()) {
            return Optional.empty();
        }
        for (JsonNode transaction : transactions) {
            JsonNode incoming = transaction.path("in_msg");
            if (incoming.path("source").asText().isBlank()
                    && sameHash(messageHash, incoming.path("hash").asText())) {
                return Optional.of(transaction);
            }
        }
        return Optional.empty();
    }
    /**
     * 执行或处理 {@code runGetMethod} 对应的业务流程，并维护状态和异常边界。
     */
    public JsonNode runGetMethod(String address, String method, JsonNode stack) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("address", address);
        body.put("method", method);
        body.set("stack", stack);
        return post("/runGetMethod", body);
    }
    /**
     * 获取或查询 {@code get} 对应的数据，供调用方读取当前状态。
     */
    private JsonNode get(String path) {
        return request("GET", path, null);
    }
    /**
     * 执行 {@code post} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private JsonNode post(String path, JsonNode body) {
        try {
            return request("POST", path, objectMapper.writeValueAsString(body));
        } catch (JacksonException e) {
            throw new IllegalStateException("TON request serialization failed", e);
        }
    }
    /**
     * 执行 {@code request} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private JsonNode request(String method, String path, String body) {
        if (fixedBaseUrl != null && !fixedBaseUrl.isBlank()) {
            return execute(method, URI.create(fixedBaseUrl + path), body, null, fixedApiKey);
        }
        String network = repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                .getNetwork();
        return rpcNodeService.withFailover(CHAIN, network,
                node -> execute(method, URI.create(trim(node.getRpcUrl()) + path), body, node,
                        node.getApiKey() == null ? "" : node.getApiKey()));
    }
    /**
     * 执行或处理 {@code execute} 对应的业务流程，并维护状态和异常边界。
     */
    private synchronized JsonNode execute(String method, URI uri, String body, ChainRpcNode node, String apiKey) {
        int attempts = 3;
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                long wait = 1_050L - (System.currentTimeMillis() - lastRequestMillis);
                if (apiKey.isBlank() && wait > 0) {
                    Thread.sleep(wait);
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .method(method, body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .header("accept", "application/json")
                        .header("user-agent", "surprising-wallet/1.0");
                if (body != null) {
                    builder.header("content-type", "application/json");
                }
                if (node != null) {
                    rpcNodeService.applyAuthHeaders(builder, node);
                } else if (!apiKey.isBlank()) {
                    builder.header("X-API-Key", apiKey);
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                lastRequestMillis = System.currentTimeMillis();
                if (response.statusCode() / 100 != 2) {
                    String message = "TON Center HTTP " + response.statusCode() + ": " + abbreviate(response.body());
                    if (isRetryable(response.statusCode()) && attempt < attempts) {
                        lastFailure = new IllegalStateException(message);
                        Thread.sleep(1_500L * attempt);
                        continue;
                    }
                    throw new IllegalStateException(message);
                }
                JsonNode root = objectMapper.readTree(response.body());
                if (!root.path("ok").asBoolean(false)) {
                    String message = "TON Center error: " + abbreviate(root.toString());
                    if (attempt < attempts && root.path("error").asText("").toLowerCase().contains("rate")) {
                        lastFailure = new IllegalStateException(message);
                        Thread.sleep(1_500L * attempt);
                        continue;
                    }
                    throw new IllegalStateException(message);
                }
                return root.path("result");
            } catch (java.io.IOException e) {
                lastFailure = new IllegalStateException("TON Center IO failed", e);
                if (attempt < attempts) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                throw lastFailure;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TON Center request interrupted", e);
            }
        }
        throw lastFailure == null ? new IllegalStateException("TON Center request failed") : lastFailure;
    }
    /**
     * 判断 {@code isRetryable} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }
    /**
     * 转换或计算 {@code sleepBeforeRetry} 对应的值，统一金额、格式和边界规则。
     */
    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(1_500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TON Center request interrupted", e);
        }
    }
    /**
     * 执行 {@code abbreviate} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
    /**
     * 编码或序列化 {@code encode} 对应的数据，生成链上或接口需要的表示。
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    /**
     * 执行 {@code trim} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String trim(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
    /**
     * 比较两个哈希是否相同（支持 Hex 和 Base64 编码的交叉比对）。
     *
     * @param first  第一个哈希字符串（Hex 或 Base64）
     * @param second 第二个哈希字符串（Hex 或 Base64）
     * @return true 表示字节级相等
     */
    static boolean sameHash(String first, String second) {
        byte[] firstBytes = decodeHash(first);
        byte[] secondBytes = decodeHash(second);
        return firstBytes != null && secondBytes != null && Arrays.equals(firstBytes, secondBytes);
    }
    /**
     * 解析或转换 {@code decodeHash} 对应的数据，并校验其格式和边界。
     */
    private static byte[] decodeHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            if (normalized.matches("(?i)[0-9a-f]{64}")) {
                return HexFormat.of().parseHex(normalized);
            }
            try {
                return Base64.getDecoder().decode(normalized);
            } catch (IllegalArgumentException ignored) {
                return Base64.getUrlDecoder().decode(normalized);
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
    /**
     * 构建或生成 {@code buildHttpClient} 对应的结果，并执行输入和状态校验。
     */
    private static HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
