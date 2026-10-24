package com.surprising.wallet.chain.tron;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TronTridentClientTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothNodePurposesMustPassTheProviderLimiter(boolean solidity) throws Exception {
        var full = ChainRpcNode.builder().nodeLabel("test-full").rpcUrl("127.0.0.1:50051").build();
        var confirmed = ChainRpcNode.builder().nodeLabel("test-solidity").rpcUrl("127.0.0.1:50052").build();
        var limits = mock(ChainRpcNodeService.class);
        var rejection = new IllegalStateException("provider limiter rejected the request");
        var expected = solidity ? confirmed : full;
        doThrow(rejection).when(limits).withProviderLimit(same(expected), any());
        try (var client = new TronTridentClient(full, confirmed, limits)) {
            var thrown = assertThrows(IllegalStateException.class, () -> {
                if (solidity) client.getTransactionInfo("00".repeat(32));
                else client.getNowBlock();
            });
            assertSame(rejection, thrown);
            verify(limits).withProviderLimit(same(expected), any());
            verifyNoMoreInteractions(limits);
        }
    }
}
