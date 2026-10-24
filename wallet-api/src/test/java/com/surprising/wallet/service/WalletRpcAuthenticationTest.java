package com.surprising.wallet.service;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.chain.evm.EvmHttpServiceFactory;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.gateway.EvmAssetRecoveryChainGateway;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletRpcAuthenticationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChainRpcNodeRepository repository = mock(ChainRpcNodeRepository.class);
    private final ChainRpcNodeService nodes = new ChainRpcNodeService(repository, "test", 1);
    private final WalletRpcClient console = new WalletRpcClient(mapper, nodes);
    private final AtomicInteger authenticated = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private final String hash = "0x" + "12".repeat(32);
    private HttpServer server;
    private Map<String, String> expectedHeaders;
    private String url;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rpc", exchange -> {
            boolean valid = expectedHeaders.entrySet().stream().allMatch(entry ->
                    entry.getValue().equals(exchange.getRequestHeaders().getFirst(entry.getKey())));
            if (!valid) {
                rejected.incrementAndGet();
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            authenticated.incrementAndGet();
            byte[] request = exchange.getRequestBody().readAllBytes();
            String method = request.length == 0 ? "" : mapper.readTree(request).path("method").asText();
            Object result = switch (method) {
                case "eth_blockNumber" -> "0x10";
                case "eth_getTransactionReceipt" -> Map.of("transactionHash", hash, "status", "0x1",
                        "blockNumber", "0xf", "blockHash", hash, "logs", List.of());
                case "eth_getBlockByNumber" -> Map.of("number", "0xf", "hash", hash, "transactions", List.of());
                default -> "test-node";
            };
            byte[] response = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "result", result))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/rpc";
    }

    @AfterEach void stop() { server.stop(0); }

    static Stream<Arguments> credentials() {
        return Stream.of(
                Arguments.of("API_KEY", "HTTP_JSON_RPC", null, "X-API-Key", "test-key"),
                Arguments.of("BEARER", "HTTP_JSON_RPC", null, "Authorization", "Bearer test-key"),
                Arguments.of("API_KEY", "HTTP_JSON_RPC", "custom-key", "custom-key", "test-key"),
                Arguments.of("NONE", "BLOCKFROST", null, "project_id", "test-key"),
                Arguments.of("BASIC", "HTTP_JSON_RPC", null, "Authorization", "Basic "
                        + Base64.getEncoder().encodeToString("test-user:test-password".getBytes(StandardCharsets.UTF_8))));
    }

    @ParameterizedTest @MethodSource("credentials")
    void consoleAndRuntimeUseIdenticalNodeCredentials(String auth, String connection, String custom,
                                                      String header, String value) throws Exception {
        expectedHeaders = Map.of(header, value);
        var node = node(auth, connection, custom);
        assertTrue(console.probe(node).success());
        assertEquals("test-node", console.jsonRpc(node, "test_method", List.of()).asText());
        try (var http = HttpClient.newHttpClient()) {
            var request = nodes.applyAuthHeaders(HttpRequest.newBuilder(URI.create(url)), node);
            assertEquals(200, http.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertEquals(3, authenticated.get());
        assertEquals(0, rejected.get());
    }

    @Test void recoveryUsesAuthenticatedNodeForReceiptCanonicalBlockAndHeight() {
        expectedHeaders = Map.of("Authorization", "Bearer test-key");
        var node = node("BEARER", "HTTP_JSON_RPC", null);
        when(repository.listRuntimeNodes("ETH", "testnet", "test", "rpc")).thenReturn(List.of(node));
        var profiles = mock(ChainProfileRepository.class);
        when(profiles.findAccountByChain("ETH")).thenReturn(Optional.of(AccountChainProfile.builder()
                .chain("ETH").network("testnet").family("evm").enabled(true).withdrawConfirmations(2).build()));
        try (var http = new EvmHttpServiceFactory()) {
            var gateway = new EvmAssetRecoveryChainGateway(profiles, mock(TokenConfigRepository.class), nodes,
                    mock(EvmAccountTransactionService.class), mapper, http);
            assertTrue(gateway.confirmed("ETH", hash));
            assertEquals(3, authenticated.get());
            assertEquals(0, rejected.get());
            assertThrows(IllegalArgumentException.class, () -> gateway.execute(
                    new EvmAssetRecoveryChainGateway.ExecutionRequest("TRON", "TRX", null, 6,
                            java.math.BigDecimal.ONE, null, "unused")));
            assertEquals(3, authenticated.get());
        }
    }

    @Test void websocketHandshakeUsesTheSameNodeAuthentication() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(5000);
            var handshake = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String key = null;
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                        int colon = line.indexOf(':');
                        if (colon <= 0) continue;
                        String name = line.substring(0, colon);
                        String value = line.substring(colon + 1).trim();
                        if (name.equalsIgnoreCase("Sec-WebSocket-Key")) key = value;
                        if (name.equalsIgnoreCase("X-API-Key")) received.set(value);
                    }
                    String accept = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return true;
                }
            });
            try {
                var node = node("API_KEY", "WS_JSON_RPC", null);
                node.setRpcUrl("ws://127.0.0.1:" + listener.getLocalPort() + "/rpc");
                assertTrue(console.probe(node).success());
                assertEquals("test-key", received.get());
            } finally {
                release.countDown();
            }
            assertTrue(handshake.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private ChainRpcNode node(String auth, String connection, String header) {
        var builder = ChainRpcNode.builder().chain("ETH").network("testnet").environment("test")
                .rpcUrl(url).connectionType(connection).authType(auth).authHeaderName(header)
                .minRequestIntervalMs(0).enabled(true);
        if ("BASIC".equals(auth)) builder.username("test-user").password("test-password");
        else builder.apiKey("test-key");
        return builder.build();
    }
}
