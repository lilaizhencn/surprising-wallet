package com.surprising.wallet.chain.evm;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Evm7702RpcClientTest {
    private static final String ADDRESS = "0x" + "1".repeat(40);
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> authentication = new AtomicReference<>();
    private HttpServer server;
    private EvmHttpServiceFactory httpServices;
    private Evm7702RpcClient rpc;
    private HttpService http;
    private Web3j web3j;

    @BeforeEach void setup() throws Exception {
        result("\"0x5208\"");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            authentication.set(exchange.getRequestHeaders().getFirst("X-Test"));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var node = ChainRpcNode.builder().chain("BASE").network("sepolia")
                .rpcUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        var nodes = mock(ChainRpcNodeService.class);
        when(nodes.enabledNodes("BASE", "sepolia", "rpc")).thenReturn(List.of(node));
        when(nodes.authHeaders(node)).thenReturn(Map.of("X-Test", "fixture-token"));
        httpServices = new EvmHttpServiceFactory();
        rpc = new Evm7702RpcClient(nodes, httpServices);
        http = rpc.http(AccountChainProfile.builder().chain("BASE").network("sepolia").build());
        web3j = Web3j.build(http);
    }

    @AfterEach void close() {
        if (web3j != null) web3j.shutdown();
        if (httpServices != null) httpServices.close();
        if (server != null) server.stop(0);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void estimatesTheSameEnvelopeThatWillBeSignedIncludingFullWidthAuthorizationValues(boolean authorized) throws Exception {
        BigInteger nonce = BigInteger.ONE.shiftLeft(63).add(BigInteger.valueOf(3));
        var tuple = new Evm7702AuthorizationService().authorize(BigInteger.valueOf(84532), ADDRESS,
                nonce, Credentials.create("11".repeat(32)));
        assertEquals(BigInteger.valueOf(21000), rpc.estimateGas(http, ADDRESS, ADDRESS, "0x1234",
                BigInteger.TWO, BigInteger.TEN, authorized ? List.of(tuple) : List.of()));
        assertEquals("fixture-token", authentication.get());
        JsonNode request = requests.getFirst();
        assertEquals("eth_estimateGas", request.path("method").asString());
        JsonNode tx = request.path("params").get(0);
        assertEquals(ADDRESS, tx.path("from").asString());
        assertEquals(ADDRESS, tx.path("to").asString());
        assertEquals("0x0", tx.path("value").asString());
        assertEquals("0x1234", tx.path("data").asString());
        assertEquals("0x2", tx.path("maxPriorityFeePerGas").asString());
        assertEquals("0xa", tx.path("maxFeePerGas").asString());
        assertEquals(authorized ? "0x4" : "0x2", tx.path("type").asString());
        if (authorized) {
            JsonNode authorization = tx.path("authorizationList").get(0);
            assertEquals(6, authorization.size());
            assertEquals(Numeric.encodeQuantity(tuple.getChainId()), authorization.path("chainId").asString());
            assertEquals(tuple.getAddress(), authorization.path("address").asString());
            assertEquals(Numeric.encodeQuantity(nonce), authorization.path("nonce").asString());
            assertEquals(Numeric.encodeQuantity(tuple.getYParity()), authorization.path("yParity").asString());
            assertEquals(Numeric.encodeQuantity(tuple.getR()), authorization.path("r").asString());
            assertEquals(Numeric.encodeQuantity(tuple.getS()), authorization.path("s").asString());
        } else {
            assertFalse(tx.has("authorizationList"));
        }
    }

    @Test void preservesStandardAndRollupFeeFieldsInTheSharedReceipt() throws Exception {
        result("""
                {"blockNumber":"0x2","gasUsed":"0x5208","effectiveGasPrice":"0x7",
                 "l1Fee":"0x123","gasUsedForL1":"0x2","operatorFeeScalar":"0x3",
                 "operatorFeeConstant":"0x4","transactionHash":"0xabc"}
                """);
        var receipt = rpc.transactionReceipt(http, "0xabc").orElseThrow();
        assertEquals(BigInteger.TWO, receipt.getBlockNumber());
        assertEquals(BigInteger.valueOf(21000), receipt.getGasUsed());
        assertEquals("0x7", receipt.getEffectiveGasPrice());
        assertEquals("0x123", receipt.getL1Fee());
        assertEquals("0x2", receipt.getGasUsedForL1());
        assertEquals("0x3", receipt.getOperatorFeeScalar());
        assertEquals("0x4", receipt.getOperatorFeeConstant());
        assertEquals("0xabc", requests.getFirst().path("params").get(0).asString());
    }

    @Test void missingReceiptRemainsPending() throws Exception {
        result("null");
        assertTrue(rpc.transactionReceipt(http, "0xpending").isEmpty());
    }

    @Test void readsUint256WithoutLongTruncationAndRejectsMalformedContractResults() throws Exception {
        BigInteger value = BigInteger.ONE.shiftLeft(200).add(BigInteger.ONE);
        result("\"0x" + Numeric.toHexStringNoPrefixZeroPadded(value, 64) + "\"");
        assertEquals(value, rpc.tokenBalance(web3j, ADDRESS, ADDRESS));
        assertEquals(value, rpc.operationNonce(web3j, ADDRESS));
        assertEquals("eth_call", requests.getFirst().path("method").asString());
        assertEquals("latest", requests.getFirst().path("params").get(1).asString());
        result("\"0x\"");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> rpc.tokenBalance(web3j, ADDRESS, ADDRESS)).getMessage().contains("malformed"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> rpc.operationNonce(web3j, ADDRESS)).getMessage().contains("malformed"));
    }

    @Test void rpcErrorsKeepTheFailedOperationAndNodeMessage() {
        response.set("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"reverted-fixture\"}}");
        assertEquals("eth_getTransactionReceipt failed: reverted-fixture", assertThrows(IllegalStateException.class,
                () -> rpc.transactionReceipt(http, "0xabc")).getMessage());
        assertEquals("eth_estimateGas failed: reverted-fixture", assertThrows(IllegalStateException.class,
                () -> rpc.estimateGas(http, ADDRESS, ADDRESS, "0x", BigInteger.ONE, BigInteger.TEN, List.of())).getMessage());
        assertEquals("operationNonce failed: reverted-fixture", assertThrows(IllegalStateException.class,
                () -> rpc.operationNonce(web3j, ADDRESS)).getMessage());
    }

    @Test void requiresTheExpectedDeployedRuntimeCode() throws Exception {
        result("\"0x6000\"");
        String hash = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray("0x6000")));
        rpc.requireCodeHash(web3j, ADDRESS, hash, "delegate");
        assertEquals("delegate runtime code hash mismatch", assertThrows(IllegalStateException.class,
                () -> rpc.requireCodeHash(web3j, ADDRESS, "0xwrong", "delegate")).getMessage());
        result("\"0x\"");
        assertEquals("collector runtime code is missing", assertThrows(IllegalStateException.class,
                () -> rpc.requireCodeHash(web3j, ADDRESS, hash, "collector")).getMessage());
    }

    @Test void aDifferentNetworkCannotBorrowTheConfiguredRpcNode() {
        assertThrows(IllegalStateException.class,
                () -> rpc.http(AccountChainProfile.builder().chain("BASE").network("mainnet").build()));
        assertTrue(requests.isEmpty());
    }

    private void result(String value) {
        response.set("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + value + "}");
    }
}
