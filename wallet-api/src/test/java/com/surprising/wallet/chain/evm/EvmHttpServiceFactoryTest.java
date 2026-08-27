package com.surprising.wallet.chain.evm;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvmHttpServiceFactoryTest {

    @Test
    void reusesSingleOkHttpClientAcrossRpcServicesAndReleasesItOnShutdown() throws Exception {
        HttpServer server = rpcServer();
        OkHttpClient client = HttpService.getOkHttpClientBuilder().build();
        EvmHttpServiceFactory factory = new EvmHttpServiceFactory(client);
        String rpcUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            for (int request = 0; request < 100; request++) {
                HttpService service = factory.create(rpcUrl);
                assertSame(client, client(service));
                Web3j web3j = Web3j.build(service);
                try {
                    assertEquals("test-client", web3j.web3ClientVersion().send().getWeb3ClientVersion());
                } finally {
                    web3j.shutdown();
                }
            }
            HttpService authenticated = factory.create(
                    rpcUrl, Map.of("Authorization", "Bearer test"));
            assertSame(client, client(authenticated));
            assertEquals("Bearer test", authenticated.getHeaders().get("Authorization"));
            assertEquals(1, client.connectionPool().connectionCount());
        } finally {
            factory.close();
            server.stop(0);
        }

        assertTrue(client.dispatcher().executorService().isShutdown());
        assertEquals(0, client.connectionPool().connectionCount());
    }

    private HttpServer rpcServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"test-client\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private OkHttpClient client(HttpService service) throws ReflectiveOperationException {
        Field field = HttpService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        return (OkHttpClient) field.get(service);
    }
}
