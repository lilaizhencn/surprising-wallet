package com.surprising.wallet.service.chain.sui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class SuiRpcClient {
    private static final String CHAIN = "SUI";
    static final String SUI_COIN_TYPE = "0x2::sui::SUI";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final OkHttpClient okHttpClient;
    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodeService;
    private final String fixedRpcUrl;

    @Autowired
    public SuiRpcClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null);
    }

    SuiRpcClient(ObjectMapper objectMapper, String rpcUrl) {
        this(objectMapper, null, null, rpcUrl);
    }

    private SuiRpcClient(ObjectMapper objectMapper, ChainJdbcRepository repository,
                         ChainRpcNodeService rpcNodeService, String fixedRpcUrl) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.fixedRpcUrl = fixedRpcUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(35))
                .build();
    }

    public long latestCheckpoint() {
        return call("sui_getLatestCheckpointSequenceNumber", objectMapper.createArrayNode()).asLong();
    }

    public BigDecimal balance(String owner, String coinType) {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(SuiHex.normalizeAddress(owner));
        params.add(coinType);
        JsonNode result = call("suix_getBalance", params);
        return new BigDecimal(result.path("totalBalance").asText("0"));
    }

    public List<SuiCoin> coins(String owner, String coinType, int limit) {
        List<SuiCoin> coins = new ArrayList<>();
        String cursor = null;
        do {
            ArrayNode params = objectMapper.createArrayNode();
            params.add(SuiHex.normalizeAddress(owner));
            params.add(coinType);
            if (cursor == null) {
                params.addNull();
            } else {
                params.add(cursor);
            }
            params.add(limit);
            JsonNode result = call("suix_getCoins", params);
            for (JsonNode item : result.path("data")) {
                coins.add(new SuiCoin(item.path("coinObjectId").asText(),
                        item.path("version").asText(),
                        item.path("digest").asText(),
                        new BigDecimal(item.path("balance").asText("0"))));
            }
            cursor = result.path("nextCursor").isNull() ? null : result.path("nextCursor").asText(null);
            if (!result.path("hasNextPage").asBoolean(false)) {
                cursor = null;
            }
        } while (cursor != null && coins.size() < limit);
        return coins;
    }

    public JsonNode queryToAddress(String address, String cursor, int limit, boolean descending) {
        ObjectNode query = objectMapper.createObjectNode();
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("ToAddress", SuiHex.normalizeAddress(address));
        query.set("filter", filter);
        query.set("options", transactionOptions());
        ArrayNode params = objectMapper.createArrayNode();
        params.add(query);
        if (cursor == null) {
            params.addNull();
        } else {
            params.add(cursor);
        }
        params.add(limit);
        params.add(descending);
        return call("suix_queryTransactionBlocks", params);
    }

    public JsonNode transactionBlock(String digest) {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(digest);
        params.add(transactionOptions());
        return call("sui_getTransactionBlock", params);
    }

    public String buildPaySui(String signer, List<String> inputCoins,
                              String recipient, long amountMist, long gasBudget) {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(SuiHex.normalizeAddress(signer));
        params.add(array(inputCoins));
        ArrayNode recipients = objectMapper.createArrayNode();
        recipients.add(SuiHex.normalizeAddress(recipient));
        params.add(recipients);
        ArrayNode amounts = objectMapper.createArrayNode();
        amounts.add(Long.toUnsignedString(amountMist));
        params.add(amounts);
        params.add(Long.toUnsignedString(gasBudget));
        return call("unsafe_paySui", params).path("txBytes").asText();
    }

    public String buildPayCoin(String signer, List<String> inputCoins,
                               String recipient, long amountAtomic,
                               String gasObjectId, long gasBudget) {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(SuiHex.normalizeAddress(signer));
        params.add(array(inputCoins));
        ArrayNode recipients = objectMapper.createArrayNode();
        recipients.add(SuiHex.normalizeAddress(recipient));
        params.add(recipients);
        ArrayNode amounts = objectMapper.createArrayNode();
        amounts.add(Long.toUnsignedString(amountAtomic));
        params.add(amounts);
        params.add(gasObjectId);
        params.add(Long.toUnsignedString(gasBudget));
        return call("unsafe_pay", params).path("txBytes").asText();
    }

    public JsonNode executeTransactionBlock(String txBytesBase64, String signatureBase64) {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(txBytesBase64);
        ArrayNode signatures = objectMapper.createArrayNode();
        signatures.add(signatureBase64);
        params.add(signatures);
        params.add(transactionOptions());
        params.add("WaitForLocalExecution");
        return call("sui_executeTransactionBlock", params);
    }

    private ObjectNode transactionOptions() {
        ObjectNode options = objectMapper.createObjectNode();
        options.put("showInput", true);
        options.put("showEffects", true);
        options.put("showEvents", true);
        options.put("showBalanceChanges", true);
        options.put("showObjectChanges", true);
        return options;
    }

    private ArrayNode array(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private JsonNode call(String method, JsonNode params) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.set("params", params);
        String requestBody = null;
        try {
            requestBody = objectMapper.writeValueAsString(body);
            if (fixedRpcUrl != null && !fixedRpcUrl.isBlank()) {
                return execute(method, requestBody, fixedRpcUrl, null);
            }
            String network = repository.findProfileByChain(CHAIN)
                    .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                    .getNetwork();
            String finalRequestBody = requestBody;
            return rpcNodeService.withFailover(CHAIN, network,
                    node -> execute(method, finalRequestBody, node.getRpcUrl(), node));
        } catch (IOException e) {
            throw new IllegalStateException("Sui RPC serialization failed for " + method, e);
        }
    }

    private JsonNode execute(String method, String requestBody, String rpcUrl, ChainRpcNode node) {
        try {
            for (int attempt = 1; attempt <= 4; attempt++) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(rpcUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("content-type", "application/json")
                        .header("accept", "application/json")
                        .header("user-agent", "surprising-wallet/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                if (node != null) {
                    rpcNodeService.applyAuthHeaders(builder, node);
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if ((response.statusCode() == 429 || response.statusCode() / 100 == 5) && attempt < 4) {
                    Thread.sleep(attempt * 1_000L);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Sui HTTP " + response.statusCode()
                            + ": " + abbreviate(response.body()));
                }
                JsonNode json = objectMapper.readTree(response.body());
                if (json.hasNonNull("error")) {
                    throw new IllegalStateException("Sui RPC " + method + " failed: "
                            + abbreviate(json.path("error").toString()));
                }
                return json.path("result");
            }
            throw new IllegalStateException("Sui retry loop exhausted for " + method);
        } catch (IOException e) {
            return callWithOkHttp(method, requestBody, rpcUrl, node);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui RPC interrupted for " + method, e);
        }
    }

    private JsonNode callWithOkHttp(String method, String requestBody, String rpcUrl, ChainRpcNode node) {
        MediaType jsonMediaType = MediaType.get("application/json; charset=utf-8");
        try {
            for (int attempt = 1; attempt <= 4; attempt++) {
                Request.Builder builder = new Request.Builder()
                        .url(rpcUrl)
                        .header("content-type", "application/json")
                        .header("accept", "application/json")
                        .header("user-agent", "surprising-wallet/1.0")
                        .post(RequestBody.create(jsonMediaType, requestBody));
                if (node != null) {
                    rpcNodeService.authHeaders(node).forEach(builder::header);
                }
                try (Response response = okHttpClient.newCall(builder.build()).execute()) {
                    String output = response.body() == null ? "" : response.body().string();
                    if ((response.code() == 429 || response.code() / 100 == 5) && attempt < 4) {
                        Thread.sleep(attempt * 1_000L);
                        continue;
                    }
                    if (response.code() / 100 != 2) {
                        throw new IllegalStateException("Sui OkHttp HTTP " + response.code()
                                + ": " + abbreviate(output));
                    }
                    JsonNode json = objectMapper.readTree(output);
                    if (json.hasNonNull("error")) {
                        throw new IllegalStateException("Sui RPC " + method + " failed: "
                                + abbreviate(json.path("error").toString()));
                    }
                    return json.path("result");
                }
            }
            throw new IllegalStateException("Sui OkHttp retry loop exhausted for " + method);
        } catch (IOException e) {
            throw new IllegalStateException("Sui OkHttp RPC IO failed for " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui OkHttp RPC interrupted for " + method, e);
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    public record SuiCoin(String objectId, String version, String digest, BigDecimal balance) {
    }
}
