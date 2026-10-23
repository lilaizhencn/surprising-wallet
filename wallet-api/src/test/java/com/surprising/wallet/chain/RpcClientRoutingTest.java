package com.surprising.wallet.chain;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.chain.aptos.AptosRpcClient;
import com.surprising.wallet.chain.hypercore.HyperCoreApiClient;
import com.surprising.wallet.chain.ton.TonApiClient;
import com.surprising.wallet.chain.ton.TonCenterClient;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.service.ChainRpcNodeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.surprising.wallet.testutil.RpcTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise real HTTP, node selection, failover and credentials through the normal constructors. */
class RpcClientRoutingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach void close() { if (server != null) server.stop(0); }

    @Test void tonIndexerAndRpcKeepTheirConfiguredAuthenticationHeaders() throws Exception {
        String base = start();
        var indexer = node("TON", base + "/indexer");
        indexer.setAuthType("bearer");
        indexer.setApiKey("test-indexer-key");
        var api = new TonApiClient(json, profiles("TON"), nodes(indexer));
        assertEquals("jetton-wallet", api.resolveJettonWallet("owner", "master"));
        var rpcNode = node("TON", base + "/toncenter");
        rpcNode.setAuthHeaderName("X-API-Key");
        rpcNode.setApiKey("test-rpc-key");
        var rpc = new TonCenterClient(json, profiles("TON"), nodes(rpcNode));
        assertEquals(42, rpc.masterchainInfo().path("last").path("seqno").asInt());
        assertEquals(List.of(
                new Request("GET", "/indexer/v2/accounts/owner/jettons/master", "Bearer test-indexer-key", null),
                new Request("GET", "/toncenter/getMasterchainInfo", null, "test-rpc-key")), requests);
    }

    @Test void aptosFaucetUsesItsOwnConfiguredPurposeAndEndpoint() throws Exception {
        String base = start();
        var rpc = new AptosRpcClient(json, profiles("APTOS"), nodes("APTOS", Map.of(
                "rpc", node("APTOS", base + "/aptos"),
                "faucet", node("APTOS", base + "/faucet"))));
        assertEquals(4, rpc.chainId());
        rpc.fundDevnetAccount("0x1", 1234);
        assertEquals("/aptos", requests.getFirst().path());
        assertEquals("POST", requests.getLast().method());
        assertEquals("/faucet/mint", requests.getLast().path());
    }

    @Test void hyperCoreUsesNextConfiguredNodeWhenTheFirstNodeRejectsTheRequest() throws Exception {
        String base = start();
        var repository = mock(ChainRpcNodeRepository.class);
        when(repository.listRuntimeNodes("HYPERCORE", "test", "dev", "info")).thenReturn(List.of(
                node("HYPERCORE", base + "/bad"), node("HYPERCORE", base + "/good")));
        var client = new HyperCoreApiClient(json, profiles("HYPERCORE"),
                new ChainRpcNodeService(repository, "dev", 1));
        assertEquals("ok", client.postInfo(json.createObjectNode().put("type", "test")).path("status").asText());
        assertEquals(List.of("/bad/info", "/good/info"), requests.stream().map(Request::path).toList());
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-API-Key")));
            exchange.getRequestBody().readAllBytes();
            String body = switch (path) {
                case "/indexer/v2/accounts/owner/jettons/master" -> "{\"wallet_address\":{\"address\":\"jetton-wallet\"}}";
                case "/toncenter/getMasterchainInfo" -> "{\"ok\":true,\"result\":{\"last\":{\"seqno\":42}}}";
                case "/aptos" -> "{\"chain_id\":4}";
                default -> "{\"status\":\"ok\"}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(path.startsWith("/bad/") ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private record Request(String method, String path, String authorization, String apiKey) { }
}
