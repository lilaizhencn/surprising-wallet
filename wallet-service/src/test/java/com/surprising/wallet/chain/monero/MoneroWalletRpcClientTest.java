package com.surprising.wallet.chain.monero;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneroWalletRpcClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callsWalletRpcAndKeepsAtomicPrecision() throws Exception {
        MoneroWalletRpcClient client = startClient();

        assertEquals(1234L, client.height());
        assertEquals(new BigInteger("123456789012"), client.toAtomic(new BigDecimal("0.123456789012")));
        assertEquals(new BigDecimal("1.234567890123"), client.fromAtomic(new BigInteger("1234567890123")));

        MoneroWalletRpcClient.Subaddress created = client.createAddress("user=7");
        assertEquals("89nCreatedSubaddress", created.address());
        assertEquals(7, created.addressIndex());

        List<MoneroWalletRpcClient.Transfer> incoming = client.incomingTransfers(100);
        assertEquals(1, incoming.size());
        assertEquals("89nRecoveredSubaddress", incoming.get(0).toAddress());
        assertEquals(5, incoming.get(0).subaddressIndex());
        assertEquals(new BigDecimal("1.234567890123"), incoming.get(0).amount());

        MoneroWalletRpcClient.Transfer sent = client.transfer(5, "89nExternalAddress", new BigDecimal("0.123456789012"));
        assertEquals("sent-tx", sent.txHash());
        JsonNode transferRequest = requests.stream()
                .filter(node -> "transfer".equals(node.path("method").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals(5, transferRequest.path("params").path("subaddr_indices").get(0).asInt());
        assertEquals("123456789012",
                transferRequest.path("params").path("destinations").get(0).path("amount").asText());
    }

    @Test
    void buildsDigestAuthorizationForWalletRpcLogin() {
        String header = MoneroWalletRpcClient.digestAuthorization(
                "POST",
                URI.create("http://127.0.0.1:18088/json_rpc"),
                "wallet",
                "secret",
                "Digest qop=\"auth\",algorithm=MD5,realm=\"monero-rpc\",nonce=\"abc123\"");

        assertTrue(header.startsWith("Digest "));
        assertTrue(header.contains("username=\"wallet\""));
        assertTrue(header.contains("realm=\"monero-rpc\""));
        assertTrue(header.contains("uri=\"/json_rpc\""));
        assertTrue(header.contains("qop=auth"));
        assertTrue(header.matches(".*response=\"[0-9a-f]{32}\".*"));
    }

    private MoneroWalletRpcClient startClient() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json_rpc", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = MAPPER.readTree(body);
            requests.add(request);
            JsonNode response = responseFor(request);
            byte[] bytes = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        return new MoneroWalletRpcClient(MAPPER, "http://127.0.0.1:" + port);
    }

    private static JsonNode responseFor(JsonNode request) {
        String method = request.path("method").asText();
        return switch (method) {
            case "get_height" -> json("""
                    {"jsonrpc":"2.0","id":"0","result":{"height":1234}}
                    """);
            case "create_address" -> json("""
                    {"jsonrpc":"2.0","id":"0","result":{"address":"89nCreatedSubaddress","address_index":7}}
                    """);
            case "get_address" -> json("""
                    {"jsonrpc":"2.0","id":"0","result":{"addresses":[{"address":"89nRecoveredSubaddress","address_index":5}]}}
                    """);
            case "get_transfers" -> {
                assertTrue(request.path("params").path("filter_by_height").asBoolean());
                assertEquals(100, request.path("params").path("min_height").asLong());
                yield json("""
                        {"jsonrpc":"2.0","id":"0","result":{"in":[{
                          "txid":"incoming-tx",
                          "amount":1234567890123,
                          "height":1200,
                          "confirmations":34,
                          "subaddr_index":{"major":0,"minor":5}
                        }]}}
                        """);
            }
            case "transfer" -> json("""
                    {"jsonrpc":"2.0","id":"0","result":{"tx_hash":"sent-tx","fee":2000000000}}
                    """);
            default -> throw new IllegalArgumentException("unexpected method: " + method);
        };
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
