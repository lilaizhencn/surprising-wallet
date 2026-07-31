package com.surprising.wallet.sdk.bitcoinj.litecoin;

/**
 * 定义费用、网络或运行策略，集中维护相关边界值。
 */
public final class LitecoinFeePolicy {
    /**
     * 定义 {@code DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE = 2L;
    /**
     * 定义 {@code MIN_FEE_RATE_LITOSHI_PER_VBYTE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long MIN_FEE_RATE_LITOSHI_PER_VBYTE = 1L;
    /**
     * 定义 {@code MAX_FEE_RATE_LITOSHI_PER_VBYTE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long MAX_FEE_RATE_LITOSHI_PER_VBYTE = 100L;
    /**
     * 定义 {@code DUST_THRESHOLD_LITOSHI} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long DUST_THRESHOLD_LITOSHI = LitecoinNetworkParameters.TESTNET_DUST_THRESHOLD_LITOSHI;

    /**
     * 构造 {@code LitecoinFeePolicy}，初始化该组件运行所需的状态和依赖。
     */
    private LitecoinFeePolicy() {
    }

    /**
     * 执行 {@code clampFeeRate} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static long clampFeeRate(long feeRate) {
        if (feeRate < MIN_FEE_RATE_LITOSHI_PER_VBYTE) {
            return MIN_FEE_RATE_LITOSHI_PER_VBYTE;
        }
        if (feeRate > MAX_FEE_RATE_LITOSHI_PER_VBYTE) {
            return MAX_FEE_RATE_LITOSHI_PER_VBYTE;
        }
        return feeRate;
    }
}
