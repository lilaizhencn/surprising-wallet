package com.surprising.wallet.service.chain.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;

@Component
public class SolanaRpcClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicLong requestId = new AtomicLong();
    private final String rpcUrl;

    @Autowired
    public SolanaRpcClient(
            @Value("${atomex.solana.rpc-url:https://api.devnet.solana.com}") String rpcUrl) {
        this(new ObjectMapper(), rpcUrl);
    }

    SolanaRpcClient(ObjectMapper objectMapper, String rpcUrl) {
        this.objectMapper = objectMapper;
        this.rpcUrl = rpcUrl;
        this.httpClient = buildHttpClient();
    }

    public boolean health() {
        return "ok".equals(call("getHealth", List.of()).asText());
    }

    public long getSlot() {
        return call("getSlot", List.of(commitment())).asLong();
    }

    public long getBalance(String address) {
        return call("getBalance", List.of(address, commitment())).path("value").asLong();
    }

    public String getLatestBlockhash() {
        return call("getLatestBlockhash", List.of(commitment()))
                .path("value").path("blockhash").asText();
    }

    public String requestAirdrop(String address, long lamports) {
        return call("requestAirdrop", List.of(address, lamports, commitment())).asText();
    }

    public String sendTransaction(byte[] serializedTransaction) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("encoding", "base64");
        config.put("skipPreflight", false);
        config.put("preflightCommitment", "confirmed");
        config.put("maxRetries", 5);
        return call("sendTransaction", List.of(
                Base64.getEncoder().encodeToString(serializedTransaction), config)).asText();
    }

    public JsonNode getSignatureStatus(String signature) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("searchTransactionHistory", true);
        JsonNode values = call("getSignatureStatuses", List.of(List.of(signature), config)).path("value");
        return values.isArray() && !values.isEmpty() ? values.get(0) : null;
    }

    public ArrayNode getSignaturesForAddress(String address, int limit) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("limit", limit);
        config.put("commitment", "confirmed");
        JsonNode result = call("getSignaturesForAddress", List.of(address, config));
        return result.isArray() ? (ArrayNode) result : objectMapper.createArrayNode();
    }

    public JsonNode getTransaction(String signature) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("encoding", "jsonParsed");
        config.put("commitment", "confirmed");
        config.put("maxSupportedTransactionVersion", 0);
        return call("getTransaction", List.of(signature, config));
    }

    public JsonNode getAccountInfo(String address) {
        ObjectNode config = commitment();
        config.put("encoding", "jsonParsed");
        return call("getAccountInfo", List.of(address, config)).path("value");
    }

    public JsonNode getTokenAccountsByOwner(String ownerAddress, String mintAddress) {
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("mint", mintAddress);
        ObjectNode config = commitment();
        config.put("encoding", "jsonParsed");
        return call("getTokenAccountsByOwner", List.of(ownerAddress, filter, config)).path("value");
    }

    public long getTokenAccountBalance(String tokenAccount) {
        return call("getTokenAccountBalance", List.of(tokenAccount, commitment()))
                .path("value").path("amount").asLong();
    }

    public JsonNode call(String method, List<?> params) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", requestId.incrementAndGet());
        payload.put("method", method);
        payload.set("params", objectMapper.valueToTree(params));
        try {
            String requestBody = objectMapper.writeValueAsString(payload);
            for (int attempt = 1; attempt <= 6; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(rpcUrl))
                            .timeout(Duration.ofSeconds(30))
                            .header("content-type", "application/json")
                            .header("user-agent", "surprising-wallet/1.0")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 429 && attempt < 6) {
                        Thread.sleep(attempt * 1_000L);
                        continue;
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("Solana RPC HTTP " + response.statusCode());
                    }
                    JsonNode body = objectMapper.readTree(response.body());
                    if (body.hasNonNull("error")) {
                        throw new IllegalStateException("Solana RPC " + method + " failed: " + body.get("error"));
                    }
                    return body.get("result");
                } catch (IOException e) {
                    if (attempt == 6) {
                        throw e;
                    }
                    Thread.sleep(attempt * 1_000L);
                }
            }
            throw new IllegalStateException("Solana RPC retry loop exhausted for " + method);
        } catch (IOException e) {
            throw new IllegalStateException("Solana RPC serialization/IO failed for " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Solana RPC interrupted for " + method, e);
        }
    }

    private ObjectNode commitment() {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("commitment", "confirmed");
        return config;
    }

    private static HttpClient buildHttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, null, null);
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("unable to initialize Solana TLS client", e);
        }
    }
}
