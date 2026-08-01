package com.surprising.wallet.chain.ltc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.BtcLikeBlock;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.BtcLikeRawTransaction;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.ScriptPubKey;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.TxOutput;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.chain.rpc.BtcLikeCommand;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@Component
public class LitecoinEsploraCommand implements BtcLikeCommand {
    /**
     * 定义 {@code LITOSHI} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigDecimal LITOSHI = new BigDecimal("100000000");
    /**
     * 保存 {@code httpClient}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final HttpClient httpClient;
    /**
     * 保存 {@code objectMapper}，用于保存业务集合或索引状态。
     */
    private final ObjectMapper objectMapper;
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code rpcNodeService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainRpcNodeService rpcNodeService;
    /**
     * 保存 {@code cachedTipHeight}，用于保存业务集合或索引状态。
     */
    private volatile long cachedTipHeight;

    /**
     * 构造 {@code LitecoinEsploraCommand}，初始化该组件运行所需的状态和依赖。
     */
    public LitecoinEsploraCommand(ChainJdbcRepository repository,
                                  ChainRpcNodeService rpcNodeService) {
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * 获取或查询 {@code getBlockCount} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public long getBlockCount() {
        cachedTipHeight = withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getblockcount", Long.class)
                : Long.parseLong(getText(node, "/blocks/tip/height")));
        return cachedTipHeight;
    }

    /**
     * 获取或查询 {@code getBlockHash} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public String getBlockHash(long height) {
        return withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getblockhash", String.class, height)
                : getText(node, "/block-height/" + height));
    }

    /**
     * 获取或查询 {@code getBlock} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public BtcLikeBlock getBlock(String hash) {
        return withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getblock", BtcLikeBlock.class, hash)
                : getEsploraBlock(node, hash));
    }
    /**
     * 获取或查询 {@code getEsploraBlock} 对应的数据，供调用方读取当前状态。
     */
    private BtcLikeBlock getEsploraBlock(ChainRpcNode node, String hash) {
        JsonNode txids = getJson(node, "/block/" + hash + "/txids");
        BtcLikeBlock block = new BtcLikeBlock();
        block.setHash(hash);
        List<String> transactions = new ArrayList<>(txids.size());
        txids.forEach(txidNode -> transactions.add(txidNode.asText()));
        block.setTx(transactions);
        return block;
    }

    /**
     * 获取或查询 {@code getRawTransaction} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid, boolean verbose) {
        return getRawTransaction(txid, verbose ? 1 : 0);
    }

    /**
     * 获取或查询 {@code getRawTransaction} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid, int verbose) {
        return withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getrawtransaction", BtcLikeRawTransaction.class, txid, verbose)
                : getEsploraRawTransaction(node, txid));
    }
    /**
     * 获取或查询 {@code getEsploraRawTransaction} 对应的数据，供调用方读取当前状态。
     */
    private BtcLikeRawTransaction getEsploraRawTransaction(ChainRpcNode node, String txid) {
        JsonNode tx = getJson(node, "/tx/" + txid);
        BtcLikeRawTransaction result = new BtcLikeRawTransaction();
        result.setTxid(tx.path("txid").asText());
        result.setVersion(tx.path("version").asInt());
        result.setLocktime(tx.path("locktime").asLong());

        JsonNode status = tx.path("status");
        if (status.path("confirmed").asBoolean(false)) {
            long blockHeight = status.path("block_height").asLong();
            result.setBlockheight(blockHeight);
            result.setBlockhash(status.path("block_hash").asText());
            result.setBlocktime(status.path("block_time").asLong());
            long tip = cachedTipHeight > 0 ? cachedTipHeight : getBlockCount();
            result.setConfirmations((int) Math.max(1L, tip - blockHeight + 1L));
        }

        List<TxOutput> outputs = new ArrayList<>();
        JsonNode vout = tx.path("vout");
        for (int i = 0; i < vout.size(); i++) {
            JsonNode output = vout.get(i);
            TxOutput mapped = new TxOutput();
            mapped.setN(i);
            mapped.setValue(BigDecimal.valueOf(output.path("value").asLong()).divide(LITOSHI));
            ScriptPubKey script = new ScriptPubKey();
            script.setHex(output.path("scriptpubkey").asText());
            script.setAsm(output.path("scriptpubkey_asm").asText());
            script.setType(output.path("scriptpubkey_type").asText());
            if (output.hasNonNull("scriptpubkey_address")) {
                script.setAddress(output.path("scriptpubkey_address").asText());
            }
            mapped.setScriptPubKey(script);
            outputs.add(mapped);
        }
        result.setVout(outputs);
        return result;
    }

    /**
     * 获取或查询 {@code getRawTransactionStr} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public String getRawTransactionStr(String txid) {
        return withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getrawtransaction", String.class, txid)
                : getText(node, "/tx/" + txid + "/hex"));
    }

    /**
     * 解析或转换 {@code decodeRawTransactionStr} 对应的数据，并校验其格式和边界。
     */
    @Override
    public BtcLikeRawTransaction decodeRawTransactionStr(String txHex) {
        return withNode(node -> {
            if (isJsonRpc(node)) {
                return callJsonRpc(node, "decoderawtransaction", BtcLikeRawTransaction.class, txHex);
            }
            throw new UnsupportedOperationException("Esplora does not expose decoderawtransaction");
        });
    }

    /**
     * 解析或转换 {@code decodeRawTransactionToString} 对应的数据，并校验其格式和边界。
     */
    @Override
    public String decodeRawTransactionToString(String txHex) {
        return withNode(node -> {
            if (isJsonRpc(node)) {
                return callJsonRpc(node, "decoderawtransaction", String.class, txHex);
            }
            throw new UnsupportedOperationException("Esplora does not expose decoderawtransaction");
        });
    }

    /**
     * 发送或广播 {@code sendRawTransaction} 对应的链上请求，并返回节点处理结果。
     */
    @Override
    public String sendRawTransaction(String hex) {
        return withNode(node -> {
            if (isJsonRpc(node)) {
                return callJsonRpc(node, "sendrawtransaction", String.class, hex);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(node) + "/tx"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(hex))
                    .build();
            return send(request).trim();
        });
    }
    /**
     * 获取或查询 {@code getJson} 对应的数据，供调用方读取当前状态。
     */
    private JsonNode getJson(ChainRpcNode node, String path) {
        try {
            return objectMapper.readTree(getText(node, path));
        } catch (Exception e) {
            throw new IllegalStateException("invalid Esplora JSON response for " + path, e);
        }
    }
    /**
     * 获取或查询 {@code getText} 对应的数据，供调用方读取当前状态。
     */
    private String getText(ChainRpcNode node, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(node) + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return send(request);
    }
    /**
     * 执行 {@code withNode} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private <T> T withNode(Function<ChainRpcNode, T> request) {
        String network = repository.findProfileByChain("LTC")
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for LTC"))
                .getNetwork();
        return rpcNodeService.withFailover("LTC", network, request);
    }
    /**
     * 执行 {@code baseUrl} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private String baseUrl(ChainRpcNode node) {
        String baseUrl = node.getRpcUrl();
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
    /**
     * 判断 {@code isJsonRpc} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private boolean isJsonRpc(ChainRpcNode node) {
        return "HTTP_JSON_RPC".equalsIgnoreCase(node.getConnectionType());
    }
    /**
     * 执行 {@code callJsonRpc} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private <T> T callJsonRpc(ChainRpcNode node, String method, Class<T> responseType, Object... params) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-type", "application/json");
            headers.putAll(rpcNodeService.authHeaders(node));
            JsonRpcHttpClient client = new JsonRpcHttpClient(new URL(node.getRpcUrl()), headers);
            client.setConnectionTimeoutMillis(120_000);
            client.setReadTimeoutMillis(120_000);
            return client.invoke(method, params, responseType);
        } catch (Throwable e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Litecoin JSON-RPC call failed: " + method, e);
        }
    }
    /**
     * 发送或广播 {@code send} 对应的链上请求，并返回节点处理结果。
     */
    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Esplora HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Esplora request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Esplora request failed: " + request.uri(), e);
        }
    }
}
