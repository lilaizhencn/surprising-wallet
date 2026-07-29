package com.surprising.wallet.custody.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Console 配置管理使用的 RPC 连通性与 JSON-RPC 客户端。
 */
@Service
class WalletRpcClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    WalletRpcClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), objectMapper);
    }

    WalletRpcClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    ProbeResult probe(String rpcUrl, String connectionType, String authType,
                      String authHeaderName, String apiKey, String username, String password) {
        long started = System.nanoTime();
        try {
            URI uri = URI.create(rpcUrl);
            if ("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme())) {
                WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(8));
                applyAuth(builder, authType, authHeaderName, apiKey, username, password);
                WebSocket socket = builder.buildAsync(uri, new WebSocket.Listener() { })
                        .get(8, TimeUnit.SECONDS);
                socket.abort();
                return new ProbeResult(true, null, elapsedMillis(started), null, Instant.now());
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8));
            applyAuth(builder, connectionType, authType, authHeaderName, apiKey, username, password);
            if (connectionType.toUpperCase(Locale.ROOT).contains("JSON_RPC")) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"web3_clientVersion\",\"params\":[]}"));
            } else {
                builder.GET();
            }
            HttpResponse<Void> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.discarding());
            long latency = elapsedMillis(started);
            boolean success = response.statusCode() >= 200 && response.statusCode() < 400;
            return new ProbeResult(success, response.statusCode(), latency,
                    success ? null : "RPC returned HTTP " + response.statusCode(), Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(
                    false, null, elapsedMillis(started), "RPC test interrupted", Instant.now());
        } catch (Exception e) {
            return new ProbeResult(
                    false, null, elapsedMillis(started), safeError(e), Instant.now());
        }
    }

    JsonNode jsonRpc(String rpcUrl, String connectionType, String authType,
                     String authHeaderName, String apiKey, String username, String password,
                     String method, String params) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(rpcUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json");
            applyAuth(builder, connectionType, authType, authHeaderName, apiKey, username, password);
            String requestBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":"
                    + objectMapper.writeValueAsString(method) + ",\"params\":" + params + "}";
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "token contract validation RPC returned HTTP " + response.statusCode());
            }
            JsonNode payload = objectMapper.readTree(response.body());
            if (payload.hasNonNull("error")) {
                String message = payload.path("error").path("message").asText("RPC error");
                throw new IllegalStateException("token contract validation failed: "
                        + message.substring(0, Math.min(message.length(), 240)));
            }
            JsonNode result = payload.get("result");
            if (result == null || result.isNull()) {
                throw new IllegalStateException("token contract validation RPC returned no result");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("token contract validation was interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("token contract validation failed: " + safeError(e), e);
        }
    }

    private static void applyAuth(
            HttpRequest.Builder builder, String connectionType, String authType,
            String authHeaderName, String apiKey, String username, String password) {
        String auth = upper(authType);
        if ("BLOCKFROST".equalsIgnoreCase(connectionType) && !blank(apiKey)) {
            builder.header("project_id", apiKey);
        } else if ("BEARER".equals(auth) && !blank(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
        } else if (apiKeyAuth(auth) && !blank(apiKey)) {
            builder.header(blank(authHeaderName) ? "X-API-Key" : authHeaderName, apiKey);
        } else if (("BASIC".equals(auth) || "DIGEST".equals(auth)) && !blank(username)) {
            builder.header("Authorization", basic(username, password));
        }
    }

    private static void applyAuth(
            WebSocket.Builder builder, String authType, String authHeaderName,
            String apiKey, String username, String password) {
        String auth = upper(authType);
        if ("BEARER".equals(auth) && !blank(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
        } else if (apiKeyAuth(auth) && !blank(apiKey)) {
            builder.header(blank(authHeaderName) ? "X-API-Key" : authHeaderName, apiKey);
        } else if ("BASIC".equals(auth) && !blank(username)) {
            builder.header("Authorization", basic(username, password));
        }
    }

    private static boolean apiKeyAuth(String auth) {
        return "API_KEY".equals(auth) || "PROJECT_ID".equals(auth)
                || "TOKEN".equals(auth) || "API_KEY_OPTIONAL".equals(auth);
    }

    private static String basic(String username, String password) {
        String value = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + value;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    record ProbeResult(boolean success, Integer statusCode, long latencyMs,
                       String error, Instant checkedAt) {
    }
}
