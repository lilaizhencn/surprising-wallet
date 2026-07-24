package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.ChainType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 区块链适配器注册表，管理所有 {@link BlockchainAdapter} 实现。
 *
 * <p>通过 Spring 自动注入所有 BlockchainAdapter bean，
 * 按 {@link ChainType} 建立索引。查找时优先精确匹配 chainType，
 * 未命中时遍历所有适配器调用 {@link BlockchainAdapter#supports(ChainType)}。
 */
@Component
public
class BlockchainAdapterRegistry {

    /** 适配器索引，key 为链类型 */
    private final Map<ChainType, BlockchainAdapter> adapters = new EnumMap<>(ChainType.class);

    /**
     * 构造注册表，Spring 自动注入所有适配器实现。
     *
     * @param adapterList 所有 BlockchainAdapter bean
     */
    public BlockchainAdapterRegistry(List<BlockchainAdapter> adapterList) {
        for (BlockchainAdapter adapter : adapterList) {
            adapters.put(adapter.chainType(), adapter);
        }
    }

    /**
     * 查找支持指定链类型的适配器。
     *
     * @param chainType 链类型
     * @return 匹配的适配器（可能为 Optional.empty()）
     */
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
