package com.surprising.wallet.chain.xrp;

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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * XRP Ledger JSON-RPC 客户端，通过 HTTP POST 与 XRPL 节点交互。
 *
 * <p>封装了 XRPL 的常用 API（server_info、account_info、account_tx、submit 等），
 * 支持节点故障转移（{@link ChainRpcNodeService#withFailover}）。
 * 生产环境通过 {@link ChainJdbcRepository} 获取节点配置，
 * 测试环境可通过构造函数直接指定固定 RPC URL。
 *
 * <p>关键数据类型：
 * <ul>
 *   <li>{@link AccountState} - 账户状态（余额、Sequence、OwnerCount）</li>
 *   <li>{@link ReserveInfo} - 准备金信息（基础准备金、每对象准备金）</li>
 * </ul>
 */
@Component
public
class XrpRpcClient {

    /** XRP 链标识 */
    private static final String CHAIN = "XRP";

    /** JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端 */
    private final HttpClient httpClient;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;

    /** 测试用固定 RPC URL（非空时优先使用） */
    private final String fixedRpcUrl;

    @Autowired
    public XrpRpcClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null);
    }

    XrpRpcClient(ObjectMapper objectMapper, String fixedRpcUrl) {
        this(objectMapper, null, null, fixedRpcUrl);
    }

    private XrpRpcClient(ObjectMapper objectMapper, ChainJdbcRepository repository,
                         ChainRpcNodeService rpcNodeService, String fixedRpcUrl) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.fixedRpcUrl = fixedRpcUrl == null ? "" : fixedRpcUrl.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
    /**
     * 获取最新已验证的账本索引。
     *
     * @return 账本索引（ledger_index/ledger_current_index）
     */
    public long latestLedgerIndex() {
        JsonNode info = call("server_info", objectMapper.createObjectNode()).path("info");
        JsonNode validated = info.path("validated_ledger");
        if (validated.has("seq")) {
            return validated.path("seq").asLong();
        }
        if (validated.has("sequence")) {
            return validated.path("sequence").asLong();
        }
        return info.path("ledger_current_index").asLong(0);
    }
    /**
     * 获取 XRPL 准备金信息。
     *
     * @return 包含 baseXrp 和 ownerXrp 的准备金信息
     */
    public ReserveInfo reserveInfo() {
        JsonNode validated = call("server_info", objectMapper.createObjectNode())
                .path("info")
                .path("validated_ledger");
        return new ReserveInfo(
                new BigDecimal(validated.path("reserve_base_xrp").asText("1")),
                new BigDecimal(validated.path("reserve_inc_xrp").asText("0.2")));
    }
    /**
     * 获取当前网络手续费（单位 drops）。
     *
     * <p>优先取 open_ledger_fee，其次 minimum_fee，最后 base_fee，最低 10 drops。
     *
     * @return 手续费（drops）
     */
    public long feeDrops() {
        JsonNode drops = call("fee", objectMapper.createObjectNode()).path("drops");
        String fee = drops.path("open_ledger_fee").asText("");
        if (fee.isBlank()) {
            fee = drops.path("minimum_fee").asText("");
        }
        if (fee.isBlank()) {
            fee = drops.path("base_fee").asText("12");
        }
        return Math.max(10L, Long.parseLong(fee));
    }
    /**
     * 查询 XRPL 账户信息。
     *
     * @param address XRPL 地址
     * @return 账户状态，如果账户未激活（actNotFound）则返回 empty
     */
    public Optional<AccountState> accountInfo(String address) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("account", address);
        params.put("ledger_index", "validated");
        try {
            JsonNode data = call("account_info", params).path("account_data");
            return Optional.of(new AccountState(
                    data.path("Account").asText(address),
                    data.path("Sequence").asLong(0),
                    new BigDecimal(data.path("Balance").asText("0")),
                    data.path("OwnerCount").asInt(0)));
        } catch (XrpRpcException e) {
            if ("actNotFound".equals(e.error())) {
                return Optional.empty();
            }
            throw e;
        }
    }
    /**
     * 获取账户当前 Sequence 号。
     *
     * @param address XRPL 地址
     * @return Sequence 号
     * @throws IllegalStateException 如果账户未激活
     */
    public long accountSequence(String address) {
        return accountInfo(address)
                .map(AccountState::sequence)
                .orElseThrow(() -> new IllegalStateException("XRPL account is not activated: " + address));
    }
    /**
     * 获取账户余额（单位 drops）。
     *
     * @param address XRPL 地址
     * @return 余额（drops），未激活返回 0
     */
    public BigDecimal accountBalanceDrops(String address) {
        return accountInfo(address).map(AccountState::balanceDrops).orElse(BigDecimal.ZERO);
    }
    /**
     * 查询账户的历史交易记录（按账本范围分页）。
     *
     * @param address   账户地址
     * @param minLedger 起始账本索引（含）
     * @param maxLedger 结束账本索引（含）
     * @param limit     返回条数上限（1-400）
     * @return 交易数组
     */
    public ArrayNode accountTransactions(String address, long minLedger, long maxLedger, int limit) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("account", address);
        params.put("ledger_index_min", minLedger);
        params.put("ledger_index_max", maxLedger);
        params.put("binary", false);
        params.put("forward", true);
        params.put("limit", Math.max(1, Math.min(limit, 400)));
        try {
            JsonNode transactions = call("account_tx", params).path("transactions");
            return transactions.isArray() ? (ArrayNode) transactions : objectMapper.createArrayNode();
        } catch (XrpRpcException e) {
            if ("actNotFound".equals(e.error())) {
                return objectMapper.createArrayNode();
            }
            throw e;
        }
    }
    /**
     * 查询账户的 TrustLine 列表。
     *
     * @param address 账户地址
     * @param peer    对方发行方地址（可选，为空则查询所有 TrustLine）
     * @return TrustLine 数组
     */
    public ArrayNode accountLines(String address, String peer) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("account", address);
        if (peer != null && !peer.isBlank()) {
            params.put("peer", peer);
        }
        params.put("ledger_index", "validated");
        try {
            JsonNode lines = call("account_lines", params).path("lines");
            return lines.isArray() ? (ArrayNode) lines : objectMapper.createArrayNode();
        } catch (XrpRpcException e) {
            if ("actNotFound".equals(e.error())) {
                return objectMapper.createArrayNode();
            }
            throw e;
        }
    }
    /**
     * 查询单笔交易详情。
     *
     * @param txHash 交易哈希
     * @return 交易详情 JSON
     */
    public JsonNode transaction(String txHash) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("transaction", txHash);
        params.put("binary", false);
        return call("tx", params);
    }
    /**
     * 提交已签名的交易到 XRPL 网络。
     *
     * @param signedTransactionHex 签名后的交易十六进制 blob
     * @return 交易哈希
     * @throws IllegalStateException 如果提交失败（engine_result 不以 tes 开头且非 terQUEUED）
     */
    public String submit(String signedTransactionHex) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("tx_blob", signedTransactionHex);
        JsonNode result = call("submit", params);
        String engineResult = result.path("engine_result").asText("");
        if (!engineResult.startsWith("tes") && !"terQUEUED".equals(engineResult)) {
            throw new IllegalStateException("XRPL submit failed: " + engineResult
                    + " " + result.path("engine_result_message").asText());
        }
        String hash = result.path("tx_json").path("hash").asText("");
        if (hash.isBlank()) {
            hash = result.path("hash").asText("");
        }
        if (hash.isBlank()) {
            throw new IllegalStateException("XRPL submit did not return transaction hash");
        }
        return hash;
    }
    private JsonNode call(String method, JsonNode paramsObject) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("method", method);
        ArrayNode params = objectMapper.createArrayNode();
        params.add(paramsObject == null ? objectMapper.createObjectNode() : paramsObject);
        body.set("params", params);
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            if (!fixedRpcUrl.isBlank()) {
                return execute(method, requestBody, fixedRpcUrl, null);
            }
            String network = repository.findProfileByChain(CHAIN)
                    .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                    .getNetwork();
            return rpcNodeService.withFailover(CHAIN, network,
                    node -> execute(method, requestBody, node.getRpcUrl(), node));
        } catch (IOException e) {
            throw new IllegalStateException("XRPL RPC serialization failed for " + method, e);
        }
    }
    private JsonNode execute(String method, String requestBody, String rpcUrl, ChainRpcNode node) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(rpcUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            if (node != null) {
                rpcNodeService.applyAuthHeaders(builder, node);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("XRPL RPC HTTP " + response.statusCode()
                        + " for " + method + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode result = root.path("result");
            String status = result.path("status").asText("");
            if ("error".equalsIgnoreCase(status) || result.hasNonNull("error")) {
                throw new XrpRpcException(result.path("error").asText(),
                        result.path("error_message").asText(result.toString()));
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("XRPL RPC interrupted for " + method, e);
        } catch (IOException e) {
            throw new IllegalStateException("XRPL RPC failed for " + method, e);
        }
    }
    /**
     * XRPL 账户状态。
     *
     * @param account      账户地址
     * @param sequence     当前 Sequence 号
     * @param balanceDrops 余额（drops）
     * @param ownerCount   持有的对象数量（TrustLine、Offer 等）
     */
    public record AccountState(String account, long sequence, BigDecimal balanceDrops, int ownerCount) {
    }

    /**
     * XRPL 准备金信息。
     *
     * @param baseXrp  基础准备金（XRP）
     * @param ownerXrp 每个对象额外准备金（XRP）
     */
    public record ReserveInfo(BigDecimal baseXrp, BigDecimal ownerXrp) {
    }

    /**
     * XRPL RPC 调用异常，包含 error 字段。
     */
    public static class XrpRpcException extends RuntimeException {
        private final String error;

        XrpRpcException(String error, String message) {
            super(message);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }
}
