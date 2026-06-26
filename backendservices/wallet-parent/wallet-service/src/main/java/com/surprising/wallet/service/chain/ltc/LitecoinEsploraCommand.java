package com.surprising.wallet.service.chain.ltc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.client.command.BtcLikeCommand;
import com.surprising.wallet.common.pojo.rpc.BtcLikeBlock;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
import com.surprising.wallet.common.pojo.rpc.ScriptPubKey;
import com.surprising.wallet.common.pojo.rpc.TxOutput;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
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
 * LitecoinSpace/Esplora adapter used when a high-throughput Litecoin Core RPC is
 * unavailable. It exposes only the Bitcoin-like calls required by the scanner
 * and broadcaster; unsupported decoder calls fail explicitly.
 */
@Component
public class LitecoinEsploraCommand implements BtcLikeCommand {
    private static final BigDecimal LITOSHI = new BigDecimal("100000000");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodeService;
    private volatile long cachedTipHeight;

    public LitecoinEsploraCommand(ChainJdbcRepository repository,
                                  ChainRpcNodeService rpcNodeService) {
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public long getBlockCount() {
        cachedTipHeight = withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getblockcount", Long.class)
                : Long.parseLong(getText(node, "/blocks/tip/height")));
        return cachedTipHeight;
    }

    @Override
    public String getBlockHash(long height) {
        return withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getblockhash", String.class, height)
                : getText(node, "/block-height/" + height));
    }

    @Override
    public BtcLikeBlock getBlock(String hash) {
        return withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getblock", BtcLikeBlock.class, hash)
                : getEsploraBlock(node, hash));
    }

    private BtcLikeBlock getEsploraBlock(ChainRpcNode node, String hash) {
        JsonNode txids = getJson(node, "/block/" + hash + "/txids");
        BtcLikeBlock block = new BtcLikeBlock();
        block.setHash(hash);
        List<String> transactions = new ArrayList<>(txids.size());
        txids.forEach(txidNode -> transactions.add(txidNode.asText()));
        block.setTx(transactions);
        return block;
    }

    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid, boolean verbose) {
        return getRawTransaction(txid, verbose ? 1 : 0);
    }

    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid, int verbose) {
        return withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getrawtransaction", BtcLikeRawTransaction.class, txid, verbose)
                : getEsploraRawTransaction(node, txid));
    }

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

    @Override
    public String getRawTransactionStr(String txid) {
        return withNode(node -> isJsonRpc(node)
                ? callJsonRpc(node, "getrawtransaction", String.class, txid)
                : getText(node, "/tx/" + txid + "/hex"));
    }

    @Override
    public BtcLikeRawTransaction decodeRawTransactionStr(String txHex) {
        return withNode(node -> {
            if (isJsonRpc(node)) {
                return callJsonRpc(node, "decoderawtransaction", BtcLikeRawTransaction.class, txHex);
            }
            throw new UnsupportedOperationException("Esplora does not expose decoderawtransaction");
        });
    }

    @Override
    public String decodeRawTransactionToString(String txHex) {
        return withNode(node -> {
            if (isJsonRpc(node)) {
                return callJsonRpc(node, "decoderawtransaction", String.class, txHex);
            }
            throw new UnsupportedOperationException("Esplora does not expose decoderawtransaction");
        });
    }

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

    private JsonNode getJson(ChainRpcNode node, String path) {
        try {
            return objectMapper.readTree(getText(node, path));
        } catch (Exception e) {
            throw new IllegalStateException("invalid Esplora JSON response for " + path, e);
        }
    }

    private String getText(ChainRpcNode node, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(node) + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return send(request);
    }

    private <T> T withNode(Function<ChainRpcNode, T> request) {
        String network = repository.findProfileByChain("LTC")
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for LTC"))
                .getNetwork();
        return rpcNodeService.withFailover("LTC", network, request);
    }

    private String baseUrl(ChainRpcNode node) {
        String baseUrl = node.getRpcUrl();
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private boolean isJsonRpc(ChainRpcNode node) {
        return "HTTP_JSON_RPC".equalsIgnoreCase(node.getConnectionType());
    }

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
