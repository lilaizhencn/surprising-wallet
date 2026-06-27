package com.surprising.wallet.service.chain.tron;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TronClientFactory {
    private static final String CHAIN = "TRON";

    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodeService;

    public TronTridentClient create() {
        AccountChainProfile profile = repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for TRON"));
        ChainRpcNode fullNode = first(rpcNodeService.enabledNodes(CHAIN, profile.getNetwork(), "rpc"),
                "missing enabled TRON full node");
        ChainRpcNode solidityNode = firstOrSame(
                rpcNodeService.enabledNodes(CHAIN, profile.getNetwork(), "solidity"), fullNode);
        return new TronTridentClient(fullNode, solidityNode, rpcNodeService);
    }

    private static ChainRpcNode first(List<ChainRpcNode> nodes, String message) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException(message);
        }
        return nodes.get(0);
    }

    private static ChainRpcNode firstOrSame(List<ChainRpcNode> nodes, ChainRpcNode fallback) {
        return nodes == null || nodes.isEmpty() ? fallback : nodes.get(0);
    }

}
