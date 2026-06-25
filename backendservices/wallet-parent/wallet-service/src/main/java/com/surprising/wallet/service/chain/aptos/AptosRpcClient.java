package com.surprising.wallet.service.chain.aptos;

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

@Component
public class AptosRpcClient {
    private static final String CHAIN = "APTOS";
    private static final String APT_COIN = "0x1::aptos_coin::AptosCoin";
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("(?i)^0x[0-9a-f]{1,64}$");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodeService;
    private final String fixedRpcUrl;
    private final String fixedFaucetUrl;

    @Autowired
    public AptosRpcClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null, null);
    }

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

    public JsonNode ledgerInfo() {
        return get("");
    }

    public int chainId() {
        return ledgerInfo().path("chain_id").asInt();
    }

    public long ledgerVersion() {
        return ledgerInfo().path("ledger_version").asLong();
    }

    public JsonNode account(String address) {
        JsonNode result = getOrNull("/accounts/" + AptosHex.normalizeAddress(address));
        return result == null ? objectMapper.createObjectNode() : result;
    }

    public long sequenceNumber(String address) {
        return account(address).path("sequence_number").asLong(0);
    }

    public long estimateGasPrice() {
        JsonNode result = getOrNull("/estimate_gas_price");
        if (result == null || result.path("gas_estimate").isMissingNode()) {
            return 100L;
        }
        return Math.max(1L, result.path("gas_estimate").asLong(100L));
    }

    public long coinBalance(String address, String coinType) {
        JsonNode balance = getOrNull("/accounts/" + AptosHex.normalizeAddress(address)
                + "/balance/" + encode(coinType));
        if (balance != null && balance.isNumber()) {
            return balance.asLong();
        }
        JsonNode resource = resource(address, coinStoreType(coinType));
        return resource.path("data").path("coin").path("value").asLong(0L);
    }

    public long coinDepositEventCounter(String address, String coinType) {
        JsonNode resource = resource(address, coinStoreType(coinType));
        return resource.path("data").path("deposit_events").path("counter").asLong(0L);
    }

    public JsonNode coinDepositEvents(String address, String coinType, long start, int limit) {
        String path = "/accounts/" + AptosHex.normalizeAddress(address)
                + "/events/" + encode(coinStoreType(coinType)) + "/deposit_events"
                + "?start=" + start + "&limit=" + limit;
        JsonNode result = getOrNull(path);
        return result == null || !result.isArray() ? objectMapper.createArrayNode() : result;
    }

    public JsonNode transactionByHash(String hash) {
        return getOrNull("/transactions/by_hash/" + hash);
    }

    public JsonNode transactionByVersion(long version) {
        return getOrNull("/transactions/by_version/" + version);
    }

    public JsonNode transactions(long startVersion, int limit) {
        JsonNode result = getOrNull("/transactions?start=" + startVersion + "&limit=" + limit);
        return result == null || !result.isArray() ? objectMapper.createArrayNode() : result;
    }

    public String submitTransaction(ObjectNode signedTransaction) {
        JsonNode result = post("/transactions", signedTransaction);
        return result.path("hash").asText();
    }

    public boolean coinStoreExists(String address, String coinType) {
        return getOrNull("/accounts/" + AptosHex.normalizeAddress(address)
                + "/resource/" + encode(coinStoreType(coinType))) != null;
    }

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

    public Optional<String> pairedMetadata(String coinType) {
        try {
            return firstAddress(view("0x1::coin::paired_metadata", List.of(coinType), List.of()))
                    .map(AptosHex::normalizeAddress);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public JsonNode fundDevnetAccount(String address, long amountOctas) {
        String path = "/mint?amount=" + amountOctas + "&address=" + AptosHex.normalizeAddress(address);
        if (fixedFaucetUrl != null && !fixedFaucetUrl.isBlank()) {
            return request("POST", fixedFaucetUrl + path, null, false, null);
        }
        String network = network();
        return rpcNodeService.withFailover(CHAIN, network, "faucet",
                node -> request("POST", trim(node.getRpcUrl()) + path, null, false, node));
    }

    private JsonNode resource(String address, String resourceType) {
        JsonNode result = getOrNull("/accounts/" + AptosHex.normalizeAddress(address)
                + "/resource/" + encode(resourceType));
        return result == null ? objectMapper.createObjectNode() : result;
    }

    private JsonNode get(String path) {
        return rpcRequest("GET", path, null, false);
    }

    private JsonNode getOrNull(String path) {
        return rpcRequest("GET", path, null, true);
    }

    private JsonNode post(String path, JsonNode body) {
        return rpcRequest("POST", path, body, false);
    }

    private JsonNode rpcRequest(String method, String path, JsonNode body, boolean nullOnNotFound) {
        if (fixedRpcUrl != null && !fixedRpcUrl.isBlank()) {
            return request(method, fixedRpcUrl + path, body, nullOnNotFound, null);
        }
        String network = network();
        return rpcNodeService.withFailover(CHAIN, network,
                node -> request(method, trim(node.getRpcUrl()) + path, body, nullOnNotFound, node));
    }

    private String network() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                .getNetwork();
    }

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

    static String aptCoinType() {
        return APT_COIN;
    }

    static String coinStoreType(String coinType) {
        return "0x1::coin::CoinStore<" + coinType + ">";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trim(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private static Optional<String> firstAddress(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isTextual() && ADDRESS_PATTERN.matcher(node.asText()).matches()) {
            return Optional.of(node.asText());
        }
        if (node.isArray() || node.isObject()) {
            for (JsonNode child : node) {
                Optional<String> address = firstAddress(child);
                if (address.isPresent()) {
                    return address;
                }
            }
        }
        return Optional.empty();
    }
}
