package com.surprising.wallet.chain.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;

/**
 * Solana JSON-RPC 客户端，通过 HTTP POST 与 Solana 节点交互。
 *
 * <p>封装了 Solana RPC 常用 API（getSlot、getBalance、getTransaction、sendTransaction、
 * getSignaturesForAddress、getTokenAccountsByOwner 等），支持：
 * <ul>
 *   <li>节点故障转移（{@link ChainRpcNodeService#withFailover}）</li>
 *   <li>自动重试（最多 6 次，含 429 限流退避）</li>
 *   <li>TLS 1.2 安全连接</li>
 * </ul>
 *
 * <p>使用 JSON-RPC 2.0 协议，每次请求自动递增 requestId。
 * 交易广播使用 base64 编码。
 */
@Component
public
class SolanaRpcClient {

    /** Solana 链标识 */
    private static final String CHAIN = "SOLANA";

    /** JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端 */
    private final HttpClient httpClient;

    /** JSON-RPC 请求 ID 生成器 */
    private final AtomicLong requestId = new AtomicLong();

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;

    /** 测试用固定 RPC URL（非 null 时优先使用） */
    private final String fixedRpcUrl;

    @Autowired
    public SolanaRpcClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null);
    }

    SolanaRpcClient(ObjectMapper objectMapper, String rpcUrl) {
        this(objectMapper, null, null, rpcUrl);
    }

    private SolanaRpcClient(ObjectMapper objectMapper, ChainJdbcRepository repository,
                            ChainRpcNodeService rpcNodeService, String fixedRpcUrl) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.fixedRpcUrl = fixedRpcUrl;
        this.httpClient = buildHttpClient();
    }
    /**
     * 检查节点健康状态。
     *
     * @return true 表示节点正常（返回 "ok"）
     */
    public boolean health() {
        return "ok".equals(call("getHealth", List.of()).asText());
    }
    /**
     * 获取当前 slot 高度。
     *
     * @return 当前 slot 号
     */
    public long getSlot() {
        return call("getSlot", List.of(commitment())).asLong();
    }
    /**
     * 查询账户 SOL 余额。
     *
     * @param address Solana 地址
     * @return 余额（lamports）
     */
    public long getBalance(String address) {
        return call("getBalance", List.of(address, commitment())).path("value").asLong();
    }
    /**
     * 获取最新区块哈希（用于交易中的 recent blockhash）。
     *
     * @return base58 编码的 blockhash
     */
    public String getLatestBlockhash() {
        return call("getLatestBlockhash", List.of(commitment()))
                .path("value").path("blockhash").asText();
    }
    /**
     * 请求空投（仅 devnet/testnet 可用）。
     *
     * @param address  接收地址
     * @param lamports 空投数量（lamports）
     * @return 交易签名
     */
    public String requestAirdrop(String address, long lamports) {
        return call("requestAirdrop", List.of(address, lamports, commitment())).asText();
    }
    /**
     * 获取免除租金所需的最低余额。
     *
     * @param dataLength 账户数据长度（字节）
     * @return 最低余额（lamports）
     */
    public long minimumBalanceForRentExemption(long dataLength) {
        if (dataLength < 0) {
            throw new IllegalArgumentException("Solana rent data length must be non-negative");
        }
        return call("getMinimumBalanceForRentExemption", List.of(dataLength)).asLong();
    }
    /**
     * 广播已签名的交易。
     *
     * @param serializedTransaction 序列化后的交易字节
     * @return 交易签名
     */
    public String sendTransaction(byte[] serializedTransaction) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("encoding", "base64");
        config.put("skipPreflight", false);
        config.put("preflightCommitment", "confirmed");
        config.put("maxRetries", 5);
        return call("sendTransaction", List.of(
                Base64.getEncoder().encodeToString(serializedTransaction), config)).asText();
    }
    /**
     * 查询签名状态（含确认次数和错误信息）。
     *
     * @param signature 交易签名
     * @return 签名状态，null 表示未找到
     */
    public JsonNode getSignatureStatus(String signature) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("searchTransactionHistory", true);
        JsonNode values = call("getSignatureStatuses", List.of(List.of(signature), config)).path("value");
        return values.isArray() && !values.isEmpty() ? values.get(0) : null;
    }
    /**
     * 获取地址的签名历史列表。
     *
     * @param address Solana 地址
     * @param limit   返回条数上限
     * @return 签名数组
     */
    public ArrayNode getSignaturesForAddress(String address, int limit) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("limit", limit);
        config.put("commitment", "confirmed");
        JsonNode result = call("getSignaturesForAddress", List.of(address, config));
        return result.isArray() ? (ArrayNode) result : objectMapper.createArrayNode();
    }
    /**
     * 获取交易详情（jsonParsed 编码）。
     *
     * @param signature 交易签名
     * @return 交易详情，包含 meta 和 instructions
     */
    public JsonNode getTransaction(String signature) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("encoding", "jsonParsed");
        config.put("commitment", "confirmed");
        config.put("maxSupportedTransactionVersion", 0);
        return call("getTransaction", List.of(signature, config));
    }
    /**
     * 获取账户信息（jsonParsed 编码）。
     *
     * @param address Solana 地址
     * @return 账户信息 value，不存在返回 null
     */
    public JsonNode getAccountInfo(String address) {
        ObjectNode config = commitment();
        config.put("encoding", "jsonParsed");
        return call("getAccountInfo", List.of(address, config)).path("value");
    }
    /**
     * 按所有者查询 SPL Token 账户。
     *
     * @param ownerAddress 所有者地址
     * @param mintAddress  Token Mint 地址
     * @return Token 账户数组
     */
    public JsonNode getTokenAccountsByOwner(String ownerAddress, String mintAddress) {
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("mint", mintAddress);
        ObjectNode config = commitment();
        config.put("encoding", "jsonParsed");
        return call("getTokenAccountsByOwner", List.of(ownerAddress, filter, config)).path("value");
    }
    /**
     * 查询 Token 账户余额。
     *
     * @param tokenAccount Token 账户地址
     * @return 余额（原子单位）
     */
    public long getTokenAccountBalance(String tokenAccount) {
        return call("getTokenAccountBalance", List.of(tokenAccount, commitment()))
                .path("value").path("amount").asLong();
    }
    public JsonNode call(String method, List<?> params) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", requestId.incrementAndGet());
        payload.put("method", method);
        payload.set("params", objectMapper.valueToTree(params));
        try {
            String requestBody = objectMapper.writeValueAsString(payload);
            if (fixedRpcUrl != null) {
                return execute(method, requestBody, fixedRpcUrl, null);
            }
            String network = repository.findProfileByChain(CHAIN)
                    .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                    .getNetwork();
            return rpcNodeService.withFailover(CHAIN, network,
                    node -> execute(method, requestBody, node.getRpcUrl(), node));
        } catch (IOException e) {
            throw new IllegalStateException("Solana RPC serialization/IO failed for " + method, e);
        }
    }
    private JsonNode execute(String method, String requestBody, String rpcUrl, ChainRpcNode node) {
        try {
            for (int attempt = 1; attempt <= 6; attempt++) {
                try {
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(rpcUrl))
                            .timeout(Duration.ofSeconds(30))
                            .header("content-type", "application/json")
                            .header("user-agent", "surprising-wallet/1.0")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                    if (node != null) {
                        rpcNodeService.applyAuthHeaders(builder, node);
                    }
                    HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 429 && attempt < 6) {
                        Thread.sleep(attempt * 1_000L);
                        continue;
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("Solana RPC HTTP " + response.statusCode());
                    }
                    JsonNode body = objectMapper.readTree(response.body());
                    if (body.hasNonNull("error")) {
                        throw new IllegalStateException("Solana RPC " + method + " failed: " + body.get("error"));
                    }
                    return body.get("result");
                } catch (IOException e) {
                    if (attempt == 6) {
                        throw e;
                    }
                    Thread.sleep(attempt * 1_000L);
                }
            }
            throw new IllegalStateException("Solana RPC retry loop exhausted for " + method);
        } catch (IOException e) {
            throw new IllegalStateException("Solana RPC IO failed for " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Solana RPC interrupted for " + method, e);
        }
    }
    private ObjectNode commitment() {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("commitment", "confirmed");
        return config;
    }
    private static HttpClient buildHttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, null, null);
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("unable to initialize Solana TLS client", e);
        }
    }
}
