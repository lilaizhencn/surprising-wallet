package com.surprising.wallet.chain.near;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 验证 {@code NearRpcClientTest} 覆盖的业务流程、边界条件和异常行为。
 */
class NearRpcClientTest {
    @Test void admittedNetworkKeepsItsRpcWhenDisabledAndAnotherNetworkIsEnabled()throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();byte[] body="{\"jsonrpc\":\"2.0\",\"id\":\"0\",\"result\":{\"chain_id\":\"testnet\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var profiles=mock(com.surprising.wallet.repository.ChainProfileRepository.class);
            when(profiles.findByChainAndNetwork("NEAR","testnet")).thenReturn(java.util.Optional.of(com.surprising.wallet.common.chain.AccountChainProfile.builder().chain("NEAR").network("testnet").enabled(false).build()));
            when(profiles.findAccountByChain("NEAR")).thenReturn(java.util.Optional.of(com.surprising.wallet.common.chain.AccountChainProfile.builder().chain("NEAR").network("mainnet").enabled(true).build()));
            var nodes=spy(com.surprising.wallet.testutil.RpcTestSupport.nodes("NEAR","http://127.0.0.1:"+server.getAddress().getPort()));
            var bound=new NearRpcClient(new tools.jackson.databind.ObjectMapper(),profiles,nodes).forNetwork("testnet");
            assertSame(bound,bound.forNetwork("testnet"));assertEquals("testnet",bound.status().path("chain_id").asText());
            verify(nodes).withFailover(eq("NEAR"),eq("testnet"),any());verify(profiles,never()).findAccountByChain(anyString());
        } finally {server.stop(0);}
    }
    @Test void unboundReadsRequireAnEnabledDefinitionAndCannotGuessADisabledNetwork() {
        var profiles=mock(com.surprising.wallet.repository.ChainProfileRepository.class);
        var nodes=mock(com.surprising.wallet.service.ChainRpcNodeService.class);
        var client=new NearRpcClient(new tools.jackson.databind.ObjectMapper(),profiles,nodes);
        assertThrows(IllegalStateException.class,client::status);assertThrows(IllegalStateException.class,()->client.forNetwork("missing"));verifyNoInteractions(nodes);
    }
    /**
     * 验证 {@code recognizesMissingAccountErrors} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void recognizesMissingAccountErrors() {
        assertTrue(NearRpcClient.isMissingAccountError(
                "NEAR RPC query failed: {\"name\":\"UNKNOWN_ACCOUNT\"}"));
        assertTrue(NearRpcClient.isMissingAccountError(
                "AccountDoesNotExist { account_id: example.testnet }"));
        assertTrue(NearRpcClient.isMissingAccountError(
                "does not exist while viewing"));
    }

    /**
     * 验证 {@code leavesOtherRpcErrorsRetryable} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void leavesOtherRpcErrorsRetryable() {
        assertFalse(NearRpcClient.isMissingAccountError("NEAR HTTP 429: rate limited"));
        assertFalse(NearRpcClient.isMissingAccountError("Invalid nonce"));
    }

    /**
     * 验证 {@code recognizesMissingBlockHeights} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void recognizesMissingBlockHeights() {
        assertTrue(NearRpcClient.isUnknownBlockError(
                "NEAR HTTP 422: {\"name\":\"UNKNOWN_BLOCK\"}"));
        assertTrue(NearRpcClient.isUnknownBlockError(
                "DB Not Found Error: BLOCK HEIGHT: 256859526 Cause: Unknown"));
        assertFalse(NearRpcClient.isUnknownBlockError("NEAR HTTP 429: rate limited"));
    }
}
