package com.surprising.wallet.chain.aptos;

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Aptos 链 REST API 客户端，封装对 Aptos FullNode REST 接口的 HTTP 调用。
 *
 * <p>提供账本信息查询、账户余额查询、交易提交、Gas 估算、view 函数调用、
 * 同质化资产元数据查询等能力。支持多节点故障转移和指数退避重试。</p>
 *
 * <p>地址格式统一通过 {@link AptosHex} 进行规范化处理。</p>
 */
@Component
public class AptosRpcClient {

    /** 链标识常量 */
    private static final String CHAIN = "APTOS";

    /** APT Coin 的完整 Move 类型标识 */
    private static final String APT_COIN = "0x1::aptos_coin::AptosCoin";

    /** Aptos 地址格式正则：0x 前缀 + 最多 64 位十六进制字符 */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("(?i)^0x[0-9a-f]{1,64}$");

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端 */
    private final HttpClient httpClient;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;

    /** 固定的 RPC URL（用于测试），为空则从数据库动态获取节点 */
    private final String fixedRpcUrl;

    /** 固定的水龙头 URL（用于测试），为空则从数据库动态获取节点 */
    private final String fixedFaucetUrl;

    /**
     * 生产环境构造器。
     *
     * @param repository     {@link ChainJdbcRepository}
     * @param rpcNodeService {@link ChainRpcNodeService}
     */
    @Autowired
    public AptosRpcClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null, null);
    }

    /**
     * 测试用构造器，使用固定的 RPC 和水龙头 URL。
     *
     * @param objectMapper JSON 工具
     * @param rpcUrl       固定 RPC URL
     * @param faucetUrl    固定水龙头 URL
     */
    AptosRpcClient(ObjectMapper objectMapper, String rpcUrl, String faucetUrl) {
        this(objectMapper, null, null, rpcUrl, faucetUrl);
    }

    private AptosRpcClient(ObjectMapper objectMapper, ChainJdbcRepository repository,
                           ChainRpcNodeService rpcNodeService, String fixedRpcUrl, String fixedFaucetUrl) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.fixedRpcUrl = trim(fixedRpcUrl);
        this.fixedFaucetUrl = trim(fixedFaucetUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 获取账本基本信息。
     *
     * @return 包含 chain_id、ledger_version 等信息的 JSON 节点
     */
    public JsonNode ledgerInfo() {
        return get("");
    }

    /**
     * 获取链 ID。
     *
     * @return 链 ID
     */
    public int chainId() {
        return ledgerInfo().path("chain_id").asInt();
    }

    /**
     * 获取当前账本版本号。
     *
     * @return 账本版本号
     */
    public long ledgerVersion() {
        return ledgerInfo().path("ledger_version").asLong();
    }

    /**
     * 查询账户信息。
     *
     * @param address 账户地址
     * @return 账户 JSON 信息，包含 sequence_number 等字段
     */
    public JsonNode account(String address) {
        JsonNode result = getOrNull("/accounts/" + AptosHex.normalizeAddress(address));
        return result == null ? objectMapper.createObjectNode() : result;
    }

    /**
     * 查询账户当前序列号。
     *
     * @param address 账户地址
     * @return 序列号
     */
    public long sequenceNumber(String address) {
        return account(address).path("sequence_number").asLong(0);
    }

    /**
     * 估算当前 Gas 价格。
     *
     * @return Gas 价格（octas/单位），最小返回 1
     */
    public long estimateGasPrice() {
        JsonNode result = getOrNull("/estimate_gas_price");
        if (result == null || result.path("gas_estimate").isMissingNode()) {
            return 100L;
        }
        return Math.max(1L, result.path("gas_estimate").asLong(100L));
    }

    /**
     * 查询 APT 余额。
     *
     * <p>优先使用新版的 balance API；如果返回非法数值，回退到读取 CoinStore 资源。</p>
     *
     * @param address 账户地址
     * @return APT 余额（octas）
     */
    public long aptBalance(String address) {
        JsonNode balance = getOrNull("/accounts/" + AptosHex.normalizeAddress(address)
                + "/balance/" + encode(APT_COIN));
        if (balance != null && balance.isNumber()) {
            return balance.asLong();
        }
        JsonNode resource = resource(address, aptCoinStoreType());
        return resource.path("data").path("coin").path("value").asLong(0L);
    }

    /**
     * 查询同质化资产余额。
     *
     * @param address         账户地址
     * @param metadataAddress FA 元数据地址
     * @return 余额（原子单位）
     */
    public long fungibleAssetBalance(String address, String metadataAddress) {
        JsonNode balance = getOrNull("/accounts/" + AptosHex.normalizeAddress(address)
                + "/balance/" + AptosHex.normalizeAddress(metadataAddress));
        return balance == null ? 0L : balance.asLong(0L);
    }

    /**
     * 查询同质化资产元数据。
     *
     * @param metadataAddress 元数据地址
     * @return 元数据 JSON
     */
    public JsonNode fungibleAssetMetadata(String metadataAddress) {
        return resource(metadataAddress, "0x1::fungible_asset::Metadata");
    }

    /**
     * 按交易哈希查询交易详情。
     *
     * @param hash 交易哈希
     * @return 交易 JSON；未找到时返回 null
     */
    public JsonNode transactionByHash(String hash) {
        return getOrNull("/transactions/by_hash/" + hash);
    }

    /**
     * 按版本号查询交易详情。
     *
     * @param version 交易版本号
     * @return 交易 JSON；未找到时返回 null
     */
    public JsonNode transactionByVersion(long version) {
        return getOrNull("/transactions/by_version/" + version);
    }

    /**
     * 分页查询交易列表。
     *
     * @param startVersion 起始版本号
     * @param limit        最大返回条数
     * @return 交易 JSON 数组；未找到时返回空数组
     */
    public JsonNode transactions(long startVersion, int limit) {
        JsonNode result = getOrNull("/transactions?start=" + startVersion + "&limit=" + limit);
        return result == null || !result.isArray() ? objectMapper.createArrayNode() : result;
    }

    /**
     * 提交已签名的交易。
     *
     * @param signedTransaction 已签名的交易 JSON
     * @return 交易哈希
     */
    public String submitTransaction(ObjectNode signedTransaction) {
        JsonNode result = post("/transactions", signedTransaction);
        return result.path("hash").asText();
    }

    /**
     * 调用 Aptos view 函数（只读调用）。
     *
     * @param function       完整函数标识，如 "0x1::aptos_account::exists_at"
     * @param typeArguments  类型参数列表
     * @param arguments      函数参数列表
     * @return view 函数返回值
     */
    public JsonNode view(String function, List<String> typeArguments, List<String> arguments) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("function", function);
        ArrayNode typeArgs = objectMapper.createArrayNode();
        typeArguments.forEach(typeArgs::add);
        body.set("type_arguments", typeArgs);
        ArrayNode args = objectMapper.createArrayNode();
        arguments.forEach(args::add);
        body.set("arguments", args);
        return post("/view", body);
    }

    /**
     * 查询 FungibleStore 的拥有者。
     *
     * @param storeAddress FungibleStore 地址
     * @return 拥有者地址；无法解析时返回空
     */
    public Optional<String> fungibleStoreOwner(String storeAddress) {
        JsonNode value = resource(storeAddress, "0x1::object::ObjectCore")
                .path("data").path("owner");
        return normalizedAddress(value);
    }

    /**
     * 查询 FungibleStore 的元数据地址。
     *
     * @param storeAddress FungibleStore 地址
     * @return 元数据地址 inner 值；无法解析时返回空
     */
    public Optional<String> fungibleStoreMetadata(String storeAddress) {
        JsonNode value = resource(storeAddress, "0x1::fungible_asset::FungibleStore")
                .path("data").path("metadata").path("inner");
        return normalizedAddress(value);
    }
    /**
     * 向开发网账户充值（水龙头）。
     *
     * @param address    账户地址
     * @param amountOctas 充值金额（octas）
     * @return 充值结果 JSON
     */
    public JsonNode fundDevnetAccount(String address, long amountOctas) {
        String path = "/mint?amount=" + amountOctas + "&address=" + AptosHex.normalizeAddress(address);
        if (fixedFaucetUrl != null && !fixedFaucetUrl.isBlank()) {
            return request("POST", fixedFaucetUrl + path, null, false, null);
        }
        String network = network();
        return rpcNodeService.withFailover(CHAIN, network, "faucet",
                node -> request("POST", trim(node.getRpcUrl()) + path, null, false, node));
    }

    /**
     * 查询链上资源。
     *
     * @param address      账户地址
     * @param resourceType 资源类型，如 "0x1::coin::CoinStore&lt;0x1::aptos_coin::AptosCoin&gt;"
     * @return 资源 JSON；未找到时返回空 ObjectNode
     */
    private JsonNode resource(String address, String resourceType) {
        JsonNode result = getOrNull("/accounts/" + AptosHex.normalizeAddress(address)
                + "/resource/" + encode(resourceType));
        return result == null ? objectMapper.createObjectNode() : result;
    }

    /**
     * GET 请求，不期望返回 null（404 会抛异常）。
     */
    private JsonNode get(String path) {
        return rpcRequest("GET", path, null, false);
    }

    /**
     * GET 请求，404 时返回 null 而不是抛异常。
     */
    private JsonNode getOrNull(String path) {
        return rpcRequest("GET", path, null, true);
    }

    /**
     * POST 请求。
     */
    private JsonNode post(String path, JsonNode body) {
        return rpcRequest("POST", path, body, false);
    }

    /**
     * 统一的 RPC 请求入口，支持固定 URL 和动态节点故障转移。
     *
     * @param method         HTTP 方法
     * @param path           REST 路径
     * @param body           请求体（可为 null）
     * @param nullOnNotFound 404 时返回 null
     * @return 响应 JSON
     */
    private JsonNode rpcRequest(String method, String path, JsonNode body, boolean nullOnNotFound) {
        if (fixedRpcUrl != null && !fixedRpcUrl.isBlank()) {
            return request(method, fixedRpcUrl + path, body, nullOnNotFound, null);
        }
        String network = network();
        return rpcNodeService.withFailover(CHAIN, network,
                node -> request(method, trim(node.getRpcUrl()) + path, body, nullOnNotFound, node));
    }

    /**
     * 获取当前链配置的网络名称。
     */
    private String network() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                .getNetwork();
    }

    /**
     * 执行单次 HTTP 请求，带指数退避重试（最多 4 次）。
     *
     * <p>对 429（限流）和 5xx（服务端错误）自动重试；404 在 nullOnNotFound 为 true 时返回 null。</p>
     *
     * @param method         HTTP 方法
     * @param url            完整 URL
     * @param body           请求体（可为 null）
     * @param nullOnNotFound 404 时返回 null
     * @param node           RPC 节点（用于认证头注入，可为 null）
     * @return 响应 JSON
     * @throws IllegalStateException 重试耗尽或 IO 异常时抛出
     */
    private JsonNode request(String method, String url, JsonNode body, boolean nullOnNotFound,
                             ChainRpcNode node) {
        String requestBody = null;
        try {
            if (body != null) {
                requestBody = objectMapper.writeValueAsString(body);
            }
            for (int attempt = 1; attempt <= 4; attempt++) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("accept", "application/json")
                        .header("user-agent", "surprising-wallet/1.0");
                if (node != null) {
                    rpcNodeService.applyAuthHeaders(builder, node);
                }
                if (requestBody == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("content-type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(requestBody));
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404 && nullOnNotFound) {
                    return null;
                }
                if ((response.statusCode() == 429 || response.statusCode() / 100 == 5) && attempt < 4) {
                    Thread.sleep(attempt * 1_000L);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Aptos HTTP " + response.statusCode()
                            + ": " + abbreviate(response.body()));
                }
                if (response.body() == null || response.body().isBlank()) {
                    return objectMapper.createObjectNode();
                }
                return objectMapper.readTree(response.body());
            }
            throw new IllegalStateException("Aptos retry loop exhausted for " + method + " " + url);
        } catch (IOException e) {
            throw new IllegalStateException("Aptos REST IO failed for " + method + " " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aptos REST interrupted for " + method + " " + url, e);
        }
    }
    /**
     * 获取 APT Coin 的完整 Move 类型标识。
     *
     * @return "0x1::aptos_coin::AptosCoin"
     */
    public static String aptCoinType() {
        return APT_COIN;
    }

    /**
     * 获取 APT CoinStore 资源类型标识。
     *
     * @return "0x1::coin::CoinStore&lt;0x1::aptos_coin::AptosCoin&gt;"
     */
    private static String aptCoinStoreType() {
        return "0x1::coin::CoinStore<" + APT_COIN + ">";
    }

    /**
     * URL 编码。
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 去除 URL 结尾的斜杠。
     */
    private static String trim(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    /**
     * 截断字符串用于错误日志（最多 500 字符）。
     */
    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    /**
     * 将 JSON 节点解析为规范化后的地址。
     *
     * <p>仅当值为文本格式且匹配 {@link #ADDRESS_PATTERN} 时才返回有效值。</p>
     *
     * @param value JSON 节点
     * @return 规范化后的地址；不合法时返回 {@link Optional#empty()}
     */
    private static Optional<String> normalizedAddress(JsonNode value) {
        if (value == null || !value.isTextual() || !ADDRESS_PATTERN.matcher(value.asText()).matches()) {
            return Optional.empty();
        }
        return Optional.of(AptosHex.normalizeAddress(value.asText()));
    }
}
