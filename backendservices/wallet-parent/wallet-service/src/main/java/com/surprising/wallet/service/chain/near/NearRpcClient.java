package com.surprising.wallet.service.chain.near;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

@Component
public class NearRpcClient {
    private static final String CHAIN = "NEAR";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodeService;
    private final String fixedRpcUrl;

    @Autowired
    public NearRpcClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null);
    }

    NearRpcClient(ObjectMapper objectMapper, String rpcUrl) {
        this(objectMapper, null, null, rpcUrl);
    }

    private NearRpcClient(ObjectMapper objectMapper, ChainJdbcRepository repository,
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

    public JsonNode account(String accountId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("request_type", "view_account");
        params.put("finality", "final");
        params.put("account_id", accountId);
        return call("query", params);
    }

    public boolean accountExists(String accountId) {
        try {
            account(accountId);
            return true;
        } catch (IllegalStateException e) {
            if (isMissingAccountError(e.getMessage())) {
                return false;
            }
            throw e;
        }
    }

    public JsonNode accessKey(String accountId, String publicKeyBase58) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("request_type", "view_access_key");
        params.put("finality", "final");
        params.put("account_id", accountId);
        params.put("public_key", "ed25519:" + publicKeyBase58);
        return call("query", params);
    }

    public BigInteger accountBalanceYocto(String accountId) {
        return new BigInteger(account(accountId).path("amount").asText("0"));
    }

    public JsonNode viewFunction(String contractAccountId, String methodName, byte[] argsJson) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("request_type", "call_function");
        params.put("finality", "final");
        params.put("account_id", contractAccountId);
        params.put("method_name", methodName);
        params.put("args_base64", Base64.getEncoder().encodeToString(argsJson == null ? new byte[0] : argsJson));
        return call("query", params);
    }

    public JsonNode viewFunctionJson(String contractAccountId, String methodName, byte[] argsJson) {
        JsonNode result = viewFunction(contractAccountId, methodName, argsJson).path("result");
        if (!result.isArray() || result.isEmpty()) {
            return objectMapper.nullNode();
        }
        byte[] bytes = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) {
            bytes[i] = (byte) (result.get(i).asInt() & 0xff);
        }
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("NEAR view function returned invalid JSON: " + methodName, e);
        }
    }

    public JsonNode finalBlock() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("finality", "final");
        return call("block", params);
    }

    public JsonNode block(long height) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("block_id", height);
        return call("block", params);
    }

    public long latestFinalBlockHeight() {
        return finalBlock().path("header").path("height").asLong(0L);
    }

    public JsonNode chunk(String chunkHash) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("chunk_id", chunkHash);
        return call("chunk", params);
    }

    public JsonNode broadcastTxCommit(String signedTransactionBase64) {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(signedTransactionBase64);
        return call("broadcast_tx_commit", params);
    }

    public JsonNode transactionStatus(String txHash, String senderAccountId) {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(txHash);
        params.add(senderAccountId);
        return call("tx", params);
    }

    private JsonNode call(String method, JsonNode params) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "0");
        body.put("method", method);
        body.set("params", params);
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            if (!fixedRpcUrl.isBlank()) {
                return execute(method, requestBody, fixedRpcUrl, null);
            }
            String network = repository.findProfileByChain(CHAIN)
                    .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                    .getNetwork();
            return rpcNodeService.withFailover(CHAIN, network, node -> execute(method, requestBody,
                    node.getRpcUrl(), node));
        } catch (IOException e) {
            throw new IllegalStateException("NEAR RPC serialization failed for " + method, e);
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
                    throw new IllegalStateException("NEAR HTTP " + response.statusCode()
                            + ": " + abbreviate(response.body()));
                }
                JsonNode json = objectMapper.readTree(response.body());
                if (json.hasNonNull("error")) {
                    throw new IllegalStateException("NEAR RPC " + method + " failed: "
                            + abbreviate(json.path("error").toString()));
                }
                return json.path("result");
            }
            throw new IllegalStateException("NEAR retry loop exhausted for " + method);
        } catch (IOException e) {
            throw new IllegalStateException("NEAR RPC IO failed for " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NEAR RPC interrupted for " + method, e);
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    static boolean isMissingAccountError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("UNKNOWN_ACCOUNT")
                || message.contains("AccountDoesNotExist")
                || message.contains("does not exist while viewing");
    }
}
