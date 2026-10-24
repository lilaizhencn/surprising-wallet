package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainRpcNode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Console 配置管理使用的 RPC 连通性与 JSON-RPC 客户端。
 */
@Service
class WalletRpcClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ChainRpcNodeService rpcNodes;

    WalletRpcClient(ObjectMapper objectMapper, ChainRpcNodeService rpcNodes) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = objectMapper;
        this.rpcNodes = rpcNodes;
    }

    ProbeResult probe(ChainRpcNode node) {
        long started = System.nanoTime();
        try {
            URI uri = URI.create(node.getRpcUrl());
            if ("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme())) {
                WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(8));
                rpcNodes.authHeaders(node).forEach(builder::header);
                WebSocket socket = rpcNodes.withProviderLimit(node, () ->
                        builder.buildAsync(uri, new WebSocket.Listener() { }).get(8, TimeUnit.SECONDS));
                socket.abort();
                return new ProbeResult(true, null, elapsedMillis(started), null, Instant.now());
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8));
            rpcNodes.authHeaders(node).forEach(builder::header);
            if (node.getConnectionType().toUpperCase(Locale.ROOT).contains("JSON_RPC")) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"web3_clientVersion\",\"params\":[]}"));
            } else {
                builder.GET();
            }
            HttpResponse<Void> response = rpcNodes.withProviderLimit(node, () -> httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.discarding()));
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

    JsonNode jsonRpc(ChainRpcNode node, String method, List<?> params) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(node.getRpcUrl()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json");
            rpcNodes.authHeaders(node).forEach(builder::header);
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", method, "params", params));
            HttpResponse<String> response = rpcNodes.withProviderLimit(node, () -> httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                    HttpResponse.BodyHandlers.ofString()));
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
