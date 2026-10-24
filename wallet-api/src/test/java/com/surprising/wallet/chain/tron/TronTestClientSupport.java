package com.surprising.wallet.chain.tron;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Live SDK probes use normal client construction without requiring a wallet database. */
final class TronTestClientSupport {
    private TronTestClientSupport() { }

    static TronTridentClient create(String fullNode, String solidityNode, String apiKey) throws Exception {
        var limits = mock(ChainRpcNodeService.class);
        doAnswer(call -> call.getArgument(1, ChainRpcNodeService.ProviderLimitedRequest.class).execute())
                .when(limits).withProviderLimit(any(ChainRpcNode.class), any());
        return new TronTridentClient(
                ChainRpcNode.builder().nodeLabel("live-test-full").rpcUrl(fullNode).apiKey(apiKey).build(),
                ChainRpcNode.builder().nodeLabel("live-test-solidity").rpcUrl(solidityNode).apiKey(apiKey).build(), limits);
    }
}
