package com.surprising.wallet.service.chain.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class TonCenterClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private long lastRequestMillis;

    @Autowired
    public TonCenterClient(
            @Value("${atomex.ton.rpc-url:https://testnet.toncenter.com/api/v2}") String baseUrl,
            @Value("${atomex.ton.api-key:}") String apiKey) {
        this(new ObjectMapper(), baseUrl, apiKey);
    }

    TonCenterClient(ObjectMapper objectMapper, String baseUrl, String apiKey) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.httpClient = buildHttpClient();
    }

    public JsonNode masterchainInfo() {
        return get("/getMasterchainInfo");
    }

    public JsonNode addressInformation(String address) {
        return get("/getAddressInformation?address=" + encode(address));
    }

    public JsonNode walletInformation(String address) {
        return get("/getWalletInformation?address=" + encode(address));
    }

    public long balance(String address) {
        return get("/getAddressBalance?address=" + encode(address)).asLong();
    }

    public long seqno(String address) {
        JsonNode wallet = get("/getWalletInformation?address=" + encode(address));
        return wallet.path("seqno").asLong(0);
    }

    public JsonNode transactions(String address, int limit) {
        return get("/getTransactions?address=" + encode(address) + "&limit=" + limit + "&archival=true");
    }

    public String sendBoc(byte[] boc) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("boc", java.util.Base64.getEncoder().encodeToString(boc));
        JsonNode result = post("/sendBocReturnHash", body);
        return result.path("hash").asText();
    }

    public JsonNode runGetMethod(String address, String method, JsonNode stack) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("address", address);
        body.put("method", method);
        body.set("stack", stack);
        return post("/runGetMethod", body);
    }

    private JsonNode get(String path) {
        return execute("GET", URI.create(baseUrl + path), null);
    }

    private JsonNode post(String path, JsonNode body) {
        try {
            return execute("POST", URI.create(baseUrl + path), objectMapper.writeValueAsString(body));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("TON request serialization failed", e);
        }
    }

    private synchronized JsonNode execute(String method, URI uri, String body) {
        int attempts = 3;
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                long wait = 1_050L - (System.currentTimeMillis() - lastRequestMillis);
                if (apiKey.isBlank() && wait > 0) {
                    Thread.sleep(wait);
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .method(method, body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .header("accept", "application/json")
                        .header("user-agent", "surprising-wallet/1.0");
                if (body != null) {
                    builder.header("content-type", "application/json");
                }
                if (!apiKey.isBlank()) {
                    builder.header("X-API-Key", apiKey);
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                lastRequestMillis = System.currentTimeMillis();
                if (response.statusCode() / 100 != 2) {
                    String message = "TON Center HTTP " + response.statusCode() + ": " + abbreviate(response.body());
                    if (isRetryable(response.statusCode()) && attempt < attempts) {
                        lastFailure = new IllegalStateException(message);
                        Thread.sleep(1_500L * attempt);
                        continue;
                    }
                    throw new IllegalStateException(message);
                }
                JsonNode root = objectMapper.readTree(response.body());
                if (!root.path("ok").asBoolean(false)) {
                    String message = "TON Center error: " + abbreviate(root.toString());
                    if (attempt < attempts && root.path("error").asText("").toLowerCase().contains("rate")) {
                        lastFailure = new IllegalStateException(message);
                        Thread.sleep(1_500L * attempt);
                        continue;
                    }
                    throw new IllegalStateException(message);
                }
                return root.path("result");
            } catch (java.io.IOException e) {
                lastFailure = new IllegalStateException("TON Center IO failed", e);
                if (attempt < attempts) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                throw lastFailure;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TON Center request interrupted", e);
            }
        }
        throw lastFailure == null ? new IllegalStateException("TON Center request failed") : lastFailure;
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(1_500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TON Center request interrupted", e);
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
