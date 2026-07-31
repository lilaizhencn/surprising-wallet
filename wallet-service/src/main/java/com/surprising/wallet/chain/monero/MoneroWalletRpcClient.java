package com.surprising.wallet.chain.monero;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HexFormat;

/**
 * Monero 钱包 RPC 客户端，封装对 monero-wallet-rpc 的 JSON-RPC 调用。
 *
 * <p>Monero 使用独立的 wallet-rpc 守护进程管理密钥和交易，本客户端通过 HTTP JSON-RPC 2.0
 * 与该守护进程交互。支持 Digest 认证（摘要认证）。</p>
 *
 * <p>主要功能：余额查询、地址管理、转账发送、转账历史查询、区块高度获取。</p>
 *
 * <p>精度：1 XMR = 10^12 原子单位。</p>
 */
@Component
public
class MoneroWalletRpcClient {

    /** 链标识 */
    static final String CHAIN = "XMR";

    /** 原生币符号 */
    static final String SYMBOL = "XMR";

    /** Monero 钱包账户索引 */
    static final int ACCOUNT_INDEX = 0;

    /** XMR 小数位数（1 XMR = 10^12 原子单位） */
    private static final int DECIMALS = 12;

    /** 原子单位换算因子 */
    private static final BigDecimal ATOMIC_FACTOR = BigDecimal.TEN.pow(DECIMALS);
    /**
     * 保存 {@code objectMapper}，用于保存业务集合或索引状态。
     */
    private final ObjectMapper objectMapper;
    /**
     * 保存 {@code httpClient}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final HttpClient httpClient;
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code rpcNodeService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainRpcNodeService rpcNodeService;
    /**
     * 保存 {@code fixedRpcUrl}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final String fixedRpcUrl;

    /**
     * 构造 {@code MoneroWalletRpcClient}，初始化该组件运行所需的状态和依赖。
     */
    @Autowired
    public MoneroWalletRpcClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null);
    }

    /**
     * 构造 {@code MoneroWalletRpcClient}，初始化该组件运行所需的状态和依赖。
     */
    MoneroWalletRpcClient(ObjectMapper objectMapper, String fixedRpcUrl) {
        this(objectMapper, null, null, fixedRpcUrl);
    }

    /**
     * 构造 {@code MoneroWalletRpcClient}，初始化该组件运行所需的状态和依赖。
     */
    private MoneroWalletRpcClient(ObjectMapper objectMapper, ChainJdbcRepository repository,
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
     * 执行 {@code height} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public long height() {
        return call("get_height", objectMapper.createObjectNode()).path("height").asLong(0L);
    }
    /**
     * 执行 {@code height} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public long height(String network, String purpose) {
        return call("get_height", objectMapper.createObjectNode(), network, purpose).path("height").asLong(0L);
    }
    /**
     * 写入或更新 {@code refresh} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void refresh() {
        call("refresh", objectMapper.createObjectNode());
    }
    /**
     * 写入或更新 {@code refresh} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void refresh(String network, String purpose) {
        call("refresh", objectMapper.createObjectNode(), network, purpose);
    }
    /**
     * 执行 {@code primaryAddress} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Subaddress primaryAddress() {
        return getAddress(0);
    }
    /**
     * 执行 {@code primaryAddress} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Subaddress primaryAddress(String network, String purpose) {
        return getAddress(0, network, purpose);
    }
    /**
     * 获取或查询 {@code getAddress} 对应的数据，供调用方读取当前状态。
     */
    public Subaddress getAddress(int subaddressIndex) {
        return getAddress(subaddressIndex, null, null);
    }
    /**
     * 获取或查询 {@code getAddress} 对应的数据，供调用方读取当前状态。
     */
    public Subaddress getAddress(int subaddressIndex, String network, String purpose) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("account_index", ACCOUNT_INDEX);
        ArrayNode indices = objectMapper.createArrayNode();
        indices.add(subaddressIndex);
        params.set("address_index", indices);
        JsonNode result = network == null || network.isBlank()
                ? call("get_address", params)
                : call("get_address", params, network, purpose);
        JsonNode addresses = result.path("addresses");
        if (addresses.isArray() && !addresses.isEmpty()) {
            JsonNode first = addresses.get(0);
            return new Subaddress(
                    first.path("address").asText(),
                    first.path("address_index").asInt(subaddressIndex));
        }
        return new Subaddress(result.path("address").asText(), subaddressIndex);
    }
    /**
     * 构建或生成 {@code createAddress} 对应的结果，并执行输入和状态校验。
     */
    public Subaddress createAddress(String label) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("account_index", ACCOUNT_INDEX);
        if (label != null && !label.isBlank()) {
            params.put("label", label);
        }
        JsonNode result = call("create_address", params);
        return new Subaddress(
                result.path("address").asText(),
                result.path("address_index").asInt());
    }
    /**
     * 执行 {@code incomingTransfers} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public List<Transfer> incomingTransfers(long minHeight) {
        return incomingTransfers(minHeight, null, null);
    }
    /**
     * 执行 {@code incomingTransfers} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public List<Transfer> incomingTransfers(long minHeight, String network, String purpose) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("in", true);
        params.put("pool", false);
        params.put("pending", false);
        params.put("failed", false);
        params.put("account_index", ACCOUNT_INDEX);
        if (minHeight > 0) {
            params.put("filter_by_height", true);
            params.put("min_height", minHeight);
        }
        JsonNode result = network == null || network.isBlank()
                ? call("get_transfers", params)
                : call("get_transfers", params, network, purpose);
        List<Transfer> transfers = new ArrayList<>();
        JsonNode incoming = result.path("in");
        if (!incoming.isArray()) {
            return transfers;
        }
        for (JsonNode item : incoming) {
            transfers.add(mapTransfer(item, "IN", network, purpose));
        }
        return transfers;
    }
    /**
     * 执行 {@code transfer} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Transfer transfer(int fromSubaddressIndex, String toAddress, BigDecimal amount) {
        return transfer(fromSubaddressIndex, toAddress, amount, null, null);
    }

    /**
     * 执行 {@code transfer} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Transfer transfer(int fromSubaddressIndex, String toAddress, BigDecimal amount,
                             String network, String purpose) {
        ObjectNode params = objectMapper.createObjectNode();
        ArrayNode destinations = objectMapper.createArrayNode();
        ObjectNode destination = objectMapper.createObjectNode();
        destination.put("address", toAddress);
        destination.put("amount", toAtomic(amount));
        destinations.add(destination);
        params.set("destinations", destinations);
        params.put("account_index", ACCOUNT_INDEX);
        ArrayNode subaddrIndices = objectMapper.createArrayNode();
        subaddrIndices.add(fromSubaddressIndex);
        params.set("subaddr_indices", subaddrIndices);
        params.put("priority", 0);
        params.put("ring_size", 16);
        params.put("unlock_time", 0);
        params.put("get_tx_key", true);
        JsonNode result = network == null || network.isBlank()
                ? call("transfer", params)
                : call("transfer", params, network, purpose);
        return new Transfer(
                result.path("tx_hash").asText(),
                "",
                toAddress,
                amount,
                result.path("fee").asLong(0L),
                0L,
                0,
                ACCOUNT_INDEX,
                fromSubaddressIndex,
                "OUT",
                result.toString());
    }
    /**
     * 执行 {@code transferByTxHash} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Transfer transferByTxHash(String txHash) {
        return transferByTxHash(txHash, null, null);
    }
    /**
     * 执行 {@code transferByTxHash} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Transfer transferByTxHash(String txHash, String network, String purpose) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("txid", txHash);
        JsonNode result = network == null || network.isBlank()
                ? call("get_transfer_by_txid", params)
                : call("get_transfer_by_txid", params, network, purpose);
        JsonNode transfer = result.path("transfer");
        if (transfer.isMissingNode() || transfer.isNull()) {
            return null;
        }
        return mapTransfer(transfer, transfer.path("type").asText("OUT").toUpperCase(), network, purpose);
    }
    /**
     * 解析 {@code fromAtomic} 对应的输入，并转换为当前业务模型。
     */
    public BigDecimal fromAtomic(BigInteger atomicAmount) {
        return new BigDecimal(atomicAmount == null ? BigInteger.ZERO : atomicAmount)
                .divide(ATOMIC_FACTOR, DECIMALS, RoundingMode.DOWN)
                .stripTrailingZeros();
    }
    /**
     * 编码 {@code toAtomic} 对应的数据，生成链上或接口所需的表示。
     */
    public BigInteger toAtomic(BigDecimal amount) {
        return amount.movePointRight(DECIMALS).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }
    /**
     * 执行 {@code mapTransfer} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Transfer mapTransfer(JsonNode item, String direction) {
        return mapTransfer(item, direction, null, null);
    }
    /**
     * 执行 {@code mapTransfer} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Transfer mapTransfer(JsonNode item, String direction, String network, String purpose) {
        JsonNode subaddr = item.path("subaddr_index");
        int subaddressIndex = subaddr.path("minor").asInt(0);
        String address = item.path("address").asText("");
        if (address.isBlank()) {
            address = network == null || network.isBlank()
                    ? getAddress(subaddressIndex).address()
                    : getAddress(subaddressIndex, network, purpose).address();
        }
        return new Transfer(
                item.path("txid").asText(),
                address,
                address,
                fromAtomic(new BigInteger(item.path("amount").asText("0"))),
                item.path("fee").asLong(0L),
                item.path("height").asLong(0L),
                item.path("confirmations").asInt(0),
                subaddr.path("major").asInt(ACCOUNT_INDEX),
                subaddressIndex,
                direction,
                item.toString());
    }
    /**
     * 执行 {@code call} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private JsonNode call(String method, JsonNode params) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "0");
        body.put("method", method);
        body.set("params", params == null ? objectMapper.createObjectNode() : params);
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            if (!fixedRpcUrl.isBlank()) {
                return execute(method, requestBody, fixedRpcUrl, null);
            }
            AccountChainProfile profile = repository.findProfileByChain(CHAIN)
                    .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
            return callSerialized(method, requestBody, profile.getNetwork(), "rpc");
        } catch (JacksonException e) {
            throw new IllegalStateException("Monero wallet-rpc serialization failed for " + method, e);
        }
    }
    /**
     * 执行 {@code call} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private JsonNode call(String method, JsonNode params, String network, String purpose) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "0");
        body.put("method", method);
        body.set("params", params == null ? objectMapper.createObjectNode() : params);
        try {
            return callSerialized(method, objectMapper.writeValueAsString(body), network, purpose);
        } catch (JacksonException e) {
            throw new IllegalStateException("Monero wallet-rpc serialization failed for " + method, e);
        }
    }
    /**
     * 执行 {@code callSerialized} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private JsonNode callSerialized(String method, String requestBody, String network, String purpose) {
        String effectivePurpose = purpose == null || purpose.isBlank() ? "rpc" : purpose;
        return rpcNodeService.withFailover(CHAIN, network, effectivePurpose,
                node -> execute(method, requestBody, node.getRpcUrl(), node));
    }
    /**
     * 执行或处理 {@code execute} 对应的业务流程，并维护状态和异常边界。
     */
    private JsonNode execute(String method, String requestBody, String rpcUrl, ChainRpcNode node) {
        String endpoint = jsonRpcEndpoint(rpcUrl);
        try {
            for (int attempt = 1; attempt <= 4; attempt++) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(60))
                        .header("content-type", "application/json")
                        .header("accept", "application/json")
                        .header("user-agent", "surprising-wallet/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                if (node != null && !isDigestAuth(node)) {
                    rpcNodeService.applyAuthHeaders(builder, node);
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 401 && node != null && hasPasswordAuth(node)) {
                    response = retryWithDigestAuth(endpoint, requestBody, node, response);
                }
                if ((response.statusCode() == 429 || response.statusCode() / 100 == 5) && attempt < 4) {
                    Thread.sleep(attempt * 1_000L);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Monero wallet-rpc HTTP " + response.statusCode()
                            + ": " + abbreviate(response.body()));
                }
                JsonNode json = objectMapper.readTree(response.body());
                if (json.hasNonNull("error")) {
                    throw new IllegalStateException("Monero wallet-rpc " + method + " failed: "
                            + abbreviate(json.path("error").toString()));
                }
                return json.path("result");
            }
            throw new IllegalStateException("Monero wallet-rpc retry loop exhausted for " + method);
        } catch (IOException e) {
            throw new IllegalStateException("Monero wallet-rpc IO failed for " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Monero wallet-rpc interrupted for " + method, e);
        }
    }

    /**
     * 处理 {@code retryWithDigestAuth} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private HttpResponse<String> retryWithDigestAuth(String endpoint, String requestBody, ChainRpcNode node,
                                                     HttpResponse<String> challengeResponse)
            throws IOException, InterruptedException {
        String challenge = challengeResponse.headers().allValues("WWW-authenticate").stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith("digest"))
                .findFirst()
                .orElse("");
        if (challenge.isBlank()) {
            return challengeResponse;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("user-agent", "surprising-wallet/1.0")
                .header("Authorization", digestAuthorization("POST", URI.create(endpoint),
                        node.getUsername(), node.getPassword(), challenge))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    /**
     * 执行 {@code digestAuthorization} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    static String digestAuthorization(String method, URI uri, String username, String password, String challenge) {
        Map<String, String> values = parseDigestChallenge(challenge);
        String realm = values.getOrDefault("realm", "");
        String nonce = values.getOrDefault("nonce", "");
        String algorithm = values.getOrDefault("algorithm", "MD5");
        String qop = firstQop(values.getOrDefault("qop", "auth"));
        String opaque = values.get("opaque");
        String nc = "00000001";
        String cnonce = md5Hex(Long.toHexString(System.nanoTime()) + ":" + username + ":" + nonce)
                .substring(0, 16);
        String requestUri = uri.getRawPath();
        if (requestUri == null || requestUri.isBlank()) {
            requestUri = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            requestUri = requestUri + "?" + uri.getRawQuery();
        }

        String ha1 = md5Hex(trim(username) + ":" + realm + ":" + trim(password));
        if ("MD5-sess".equalsIgnoreCase(algorithm)) {
            ha1 = md5Hex(ha1 + ":" + nonce + ":" + cnonce);
        }
        String ha2 = md5Hex(method + ":" + requestUri);
        String response = qop.isBlank()
                ? md5Hex(ha1 + ":" + nonce + ":" + ha2)
                : md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        StringBuilder header = new StringBuilder("Digest ");
        appendDigestPart(header, "username", trim(username), true);
        appendDigestPart(header, "realm", realm, true);
        appendDigestPart(header, "nonce", nonce, true);
        appendDigestPart(header, "uri", requestUri, true);
        appendDigestPart(header, "response", response, true);
        if (!algorithm.isBlank()) {
            appendDigestPart(header, "algorithm", algorithm, false);
        }
        if (!qop.isBlank()) {
            appendDigestPart(header, "qop", qop, false);
            appendDigestPart(header, "nc", nc, false);
            appendDigestPart(header, "cnonce", cnonce, true);
        }
        if (opaque != null && !opaque.isBlank()) {
            appendDigestPart(header, "opaque", opaque, true);
        }
        return header.toString();
    }
    /**
     * 解析或转换 {@code parseDigestChallenge} 对应的数据，并校验其格式和边界。
     */
    private static Map<String, String> parseDigestChallenge(String challenge) {
        String value = challenge == null ? "" : challenge.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("digest")) {
            value = value.substring("digest".length()).trim();
        }
        Map<String, String> result = new LinkedHashMap<>();
        int index = 0;
        while (index < value.length()) {
            while (index < value.length()
                    && (value.charAt(index) == ',' || Character.isWhitespace(value.charAt(index)))) {
                index++;
            }
            int equals = value.indexOf('=', index);
            if (equals < 0) {
                break;
            }
            String key = value.substring(index, equals).trim().toLowerCase(Locale.ROOT);
            index = equals + 1;
            String parsed;
            if (index < value.length() && value.charAt(index) == '"') {
                StringBuilder builder = new StringBuilder();
                index++;
                while (index < value.length()) {
                    char current = value.charAt(index++);
                    if (current == '"') {
                        break;
                    }
                    if (current == '\\' && index < value.length()) {
                        current = value.charAt(index++);
                    }
                    builder.append(current);
                }
                parsed = builder.toString();
            } else {
                int comma = value.indexOf(',', index);
                if (comma < 0) {
                    parsed = value.substring(index).trim();
                    index = value.length();
                } else {
                    parsed = value.substring(index, comma).trim();
                    index = comma + 1;
                }
            }
            result.put(key, parsed);
        }
        return result;
    }
    /**
     * 执行 {@code firstQop} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String firstQop(String qop) {
        String value = qop == null ? "" : qop.trim();
        if (value.isBlank()) {
            return "";
        }
        for (String part : value.split(",")) {
            if ("auth".equalsIgnoreCase(part.trim())) {
                return "auth";
            }
        }
        return value.split(",")[0].trim();
    }
    /**
     * 添加 {@code appendDigestPart} 对应的业务对象，并更新当前组件的集合或索引。
     */
    private static void appendDigestPart(StringBuilder builder, String key, String value, boolean quoted) {
        if (builder.length() > "Digest ".length()) {
            builder.append(", ");
        }
        builder.append(key).append('=');
        if (quoted) {
            builder.append('"').append(value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        } else {
            builder.append(value == null ? "" : value);
        }
    }
    /**
     * 判断 {@code hasPasswordAuth} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static boolean hasPasswordAuth(ChainRpcNode node) {
        return !trim(node.getUsername()).isBlank() || !trim(node.getPassword()).isBlank();
    }
    /**
     * 判断 {@code isDigestAuth} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static boolean isDigestAuth(ChainRpcNode node) {
        return "DIGEST".equalsIgnoreCase(trim(node.getAuthType()));
    }
    /**
     * 执行 {@code md5Hex} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String md5Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 digest unavailable", e);
        }
    }
    /**
     * 编码 {@code jsonRpcEndpoint} 对应的数据，生成链上或接口所需的表示。
     */
    private static String jsonRpcEndpoint(String rpcUrl) {
        String value = rpcUrl == null ? "" : rpcUrl.trim().replaceAll("/+$", "");
        if (value.endsWith("/json_rpc")) {
            return value;
        }
        return value + "/json_rpc";
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
     * 执行 {@code trim} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
    public record Subaddress(String address, int addressIndex) {
    }

    public record Transfer(String txHash, String fromAddress, String toAddress, BigDecimal amount, long feeAtomic,
                           long blockHeight, int confirmations, int accountIndex, int subaddressIndex,
                           String direction, String rawPayload) {
    }
}
