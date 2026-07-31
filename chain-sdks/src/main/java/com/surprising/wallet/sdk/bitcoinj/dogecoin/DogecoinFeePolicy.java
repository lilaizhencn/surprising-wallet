package com.surprising.wallet.sdk.bitcoinj.dogecoin;

/**
 * 定义费用、网络或运行策略，集中维护相关边界值。
 */
public final class DogecoinFeePolicy {
    /**
     * 定义 {@code KOINU_PER_DOGE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long KOINU_PER_DOGE = 100_000_000L;
    /**
     * 定义 {@code RECOMMENDED_FEE_KOINU_PER_KB} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long RECOMMENDED_FEE_KOINU_PER_KB = 1_000_000L;
    /**
     * 定义 {@code DEFAULT_FEE_RATE_KOINU_PER_BYTE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long DEFAULT_FEE_RATE_KOINU_PER_BYTE = 1_000L;
    /**
     * 定义 {@code MIN_RELAY_FEE_RATE_KOINU_PER_BYTE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long MIN_RELAY_FEE_RATE_KOINU_PER_BYTE = 100L;
    /**
     * 定义 {@code MAX_FEE_RATE_KOINU_PER_BYTE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long MAX_FEE_RATE_KOINU_PER_BYTE = 100_000L;
    /**
     * 定义 {@code HARD_DUST_THRESHOLD_KOINU} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long HARD_DUST_THRESHOLD_KOINU = 100_000L;
    /**
     * 定义 {@code RECOMMENDED_DUST_THRESHOLD_KOINU} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long RECOMMENDED_DUST_THRESHOLD_KOINU = 1_000_000L;

    /**
     * 构造 {@code DogecoinFeePolicy}，初始化该组件运行所需的状态和依赖。
     */
    private DogecoinFeePolicy() {
    }

    /**
     * 执行 {@code clampFeeRate} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static long clampFeeRate(long koinuPerByte) {
        return Math.max(MIN_RELAY_FEE_RATE_KOINU_PER_BYTE,
                Math.min(MAX_FEE_RATE_KOINU_PER_BYTE, koinuPerByte));
    }

    /**
     * 转换或计算 {@code feeForBytes} 对应的值，统一金额、格式和边界规则。
     */
    public static long feeForBytes(long bytes, long koinuPerByte) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("transaction bytes must be positive");
        }
        return Math.multiplyExact(bytes, clampFeeRate(koinuPerByte));
    }
}
