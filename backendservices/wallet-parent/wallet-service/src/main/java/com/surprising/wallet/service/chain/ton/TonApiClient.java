package com.surprising.wallet.service.chain.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
public class TonApiClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;

    @Autowired
    public TonApiClient(
            @Value("${atomex.ton.indexer-url:https://testnet.tonapi.io}") String baseUrl,
            @Value("${atomex.ton.indexer-api-key:}") String apiKey) {
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
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
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("accept", "application/json")
                    .header("user-agent", "surprising-wallet/1.0")
                    .GET();
            if (!apiKey.isBlank()) {
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
