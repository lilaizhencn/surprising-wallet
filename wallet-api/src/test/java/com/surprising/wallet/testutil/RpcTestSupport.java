package com.surprising.wallet.testutil;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.service.ChainRpcNodeService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local endpoints enter through the same profile, routing, failover and authentication code. */
public final class RpcTestSupport {
    private RpcTestSupport() { }

    public static ChainProfileRepository profiles(String chain) {
        var repository = mock(ChainProfileRepository.class);
        when(repository.findAccountByChain(chain)).thenReturn(Optional.of(AccountChainProfile.builder()
                .chain(chain).network("test").enabled(true).build()));
        return repository;
    }

    public static ChainRpcNode node(String chain, String url) {
        return ChainRpcNode.builder().chain(chain).network("test").rpcUrl(url)
                .authType("none").enabled(true).build();
    }

    public static ChainRpcNodeService nodes(String chain, String url) {
        return nodes(node(chain, url));
    }

    public static ChainRpcNodeService nodes(ChainRpcNode node) {
        return nodes(node.getChain(), Map.of("rpc", node));
    }

    public static ChainRpcNodeService nodes(String chain, Map<String, ChainRpcNode> byPurpose) {
        var repository = mock(ChainRpcNodeRepository.class);
        when(repository.listRuntimeNodes(eq(chain), anyString(), eq("dev"), nullable(String.class)))
                .thenAnswer(call -> {
                    String purpose = call.getArgument(3);
                    var node = byPurpose.getOrDefault(purpose, byPurpose.get("rpc"));
                    return node == null ? List.of() : List.of(node);
                });
        return new ChainRpcNodeService(repository, "dev", 1);
    }
}
