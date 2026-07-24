package com.surprising.wallet.service.chain.xrp;

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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Component
public
class XrpRpcClient {
    private static final String CHAIN = "XRP";    private final ObjectMapper objectMapper;    private final HttpClient httpClient;    private final ChainJdbcRepository repository;    private final ChainRpcNodeService rpcNodeService;    private final String fixedRpcUrl;

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
    public ReserveInfo reserveInfo() {
        JsonNode validated = call("server_info", objectMapper.createObjectNode())
                .path("info")
                .path("validated_ledger");
        return new ReserveInfo(
                new BigDecimal(validated.path("reserve_base_xrp").asText("1")),
                new BigDecimal(validated.path("reserve_inc_xrp").asText("0.2")));
    }
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
    public long accountSequence(String address) {
        return accountInfo(address)
                .map(AccountState::sequence)
                .orElseThrow(() -> new IllegalStateException("XRPL account is not activated: " + address));
    }
    public BigDecimal accountBalanceDrops(String address) {
        return accountInfo(address).map(AccountState::balanceDrops).orElse(BigDecimal.ZERO);
    }
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
    public JsonNode transaction(String txHash) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("transaction", txHash);
        params.put("binary", false);
        return call("tx", params);
    }
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
    public record AccountState(String account, long sequence, BigDecimal balanceDrops, int ownerCount) {
    }
    public record ReserveInfo(BigDecimal baseXrp, BigDecimal ownerXrp) {
    }
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
