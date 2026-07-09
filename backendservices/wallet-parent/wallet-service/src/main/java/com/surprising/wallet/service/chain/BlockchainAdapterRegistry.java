package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.ChainType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class BlockchainAdapterRegistry {
    private final Map<ChainType, BlockchainAdapter> adapters = new EnumMap<>(ChainType.class);

    public BlockchainAdapterRegistry(List<BlockchainAdapter> adapterList) {
        for (BlockchainAdapter adapter : adapterList) {
            adapters.put(adapter.chainType(), adapter);
        }
    }

    public Optional<BlockchainAdapter> find(ChainType chainType) {
        BlockchainAdapter adapter = adapters.get(chainType);
        if (adapter != null) {
            return Optional.of(adapter);
        }
        return adapters.values().stream().filter(candidate -> candidate.supports(chainType)).findFirst();
    }

    public BlockchainAdapter require(ChainType chainType) {
        return find(chainType).orElseThrow(() ->
                new IllegalArgumentException("No adapter registered for " + chainType));
    }
}
