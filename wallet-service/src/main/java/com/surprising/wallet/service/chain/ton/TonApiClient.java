package com.surprising.wallet.service.chain.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public
class TonApiClient {
    private static final String CHAIN = "TON";
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodeService;
    private final String fixedBaseUrl;
    private final String fixedApiKey;

    @Autowired
    public TonApiClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this.objectMapper = new ObjectMapper();
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.fixedBaseUrl = "";
        this.fixedApiKey = "";
        this.httpClient = buildHttpClient();
    }

    TonApiClient(ObjectMapper objectMapper, String baseUrl, String apiKey) {
        this.objectMapper = objectMapper;
        this.repository = null;
        this.rpcNodeService = null;
        this.fixedBaseUrl = trim(baseUrl);
        this.fixedApiKey = apiKey == null ? "" : apiKey;
        this.httpClient = buildHttpClient();
    }
    public String resolveJettonWallet(String ownerAddress, String jettonMaster) {
        JsonNode result = get("/v2/accounts/" + encode(ownerAddress)
                + "/jettons/" + encode(jettonMaster));
        String address = result.path("wallet_address").path("address").asText();
        if (address.isBlank()) {
            throw new IllegalStateException("TON API did not return a jetton wallet address");
        }
        return address;
    }
    private JsonNode get(String path) {
        if (!fixedBaseUrl.isBlank()) {
            return execute(fixedBaseUrl + path, null, fixedApiKey);
        }
        String network = repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                .getNetwork();
        return rpcNodeService.withFailover(CHAIN, network, "indexer",
                node -> execute(trim(node.getRpcUrl()) + path, node, ""));
    }
    private JsonNode execute(String url, ChainRpcNode node, String apiKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("accept", "application/json")
                    .header("user-agent", "surprising-wallet/1.0")
                    .GET();
            if (node != null) {
                rpcNodeService.applyAuthHeaders(builder, node);
            } else if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("TON API HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("TON API IO failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TON API interrupted", e);
        }
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    private static String trim(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
    private static HttpClient buildHttpClient() {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, null, null);
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(context)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("unable to initialize TON API TLS client", e);
        }
    }
}
