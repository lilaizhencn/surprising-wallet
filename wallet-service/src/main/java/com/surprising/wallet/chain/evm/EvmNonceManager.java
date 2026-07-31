package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.ChainType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EVM 账户 nonce 管理器：维护本地乐观状态，并结合链上 nonce 分配可用交易序号。
 */
@Component
public class EvmNonceManager {
    /**
     * 保存 {@code nonceByAddress}，表示链、网络、资产或代币配置。
     */
    private final Map<String, AtomicLong> nonceByAddress = new ConcurrentHashMap<>();
    /**
     * 返回本地已观察 nonce 与链上 nonce 中较大的值，但不推进本地状态。
     */
    public long peek(ChainType chainType, String address, long chainNonce) {
        return Math.max(local(chainType, address), chainNonce);
    }
    /**
     * 原子预留下一个可用 nonce，并将本地状态推进到该 nonce 之后。
     */
    public long reserve(ChainType chainType, String address, long chainNonce) {
        String key = key(chainType, address);
        AtomicLong state = nonceByAddress.computeIfAbsent(key, ignored -> new AtomicLong(chainNonce));
        return state.updateAndGet(previous -> Math.max(previous, chainNonce) + 1L) - 1L;
    }
    /**
     * 观察链上 nonce，并将本地 nonce 状态推进到不小于链上值的位置。
     */
    public void observe(ChainType chainType, String address, long chainNonce) {
        nonceByAddress.compute(key(chainType, address), (ignored, current) -> {
            if (current == null) {
                return new AtomicLong(chainNonce);
            }
            current.accumulateAndGet(chainNonce, Math::max);
            return current;
        });
    }
    /**
     * 返回指定链和地址当前记录的本地 nonce；尚未观察到时返回零。
     */
    public long local(ChainType chainType, String address) {
        AtomicLong current = nonceByAddress.get(key(chainType, address));
        return current == null ? 0L : current.get();
    }
    /**
     * 生成链与地址组合的大小写不敏感索引键。
     */
    private String key(ChainType chainType, String address) {
        return Objects.requireNonNull(chainType, "chainType").name() + ":" + Objects.requireNonNull(address, "address").toLowerCase();
    }
}
