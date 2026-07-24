package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.ChainType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic nonce allocator with optimistic local state.
 */
@Component
public class EvmNonceManager {
    private final Map<String, AtomicLong> nonceByAddress = new ConcurrentHashMap<>();
    public long peek(ChainType chainType, String address, long chainNonce) {
        return Math.max(local(chainType, address), chainNonce);
    }
    public long reserve(ChainType chainType, String address, long chainNonce) {
        String key = key(chainType, address);
        AtomicLong state = nonceByAddress.computeIfAbsent(key, ignored -> new AtomicLong(chainNonce));
        return state.updateAndGet(previous -> Math.max(previous, chainNonce) + 1L) - 1L;
    }
    public void observe(ChainType chainType, String address, long chainNonce) {
        nonceByAddress.compute(key(chainType, address), (ignored, current) -> {
            if (current == null) {
                return new AtomicLong(chainNonce);
            }
            current.accumulateAndGet(chainNonce, Math::max);
            return current;
        });
    }
    public long local(ChainType chainType, String address) {
        AtomicLong current = nonceByAddress.get(key(chainType, address));
        return current == null ? 0L : current.get();
    }
    private String key(ChainType chainType, String address) {
        return Objects.requireNonNull(chainType, "chainType").name() + ":" + Objects.requireNonNull(address, "address").toLowerCase();
    }
}
