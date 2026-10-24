package com.surprising.wallet.sdk.bitcoinj.litecoin;

/**
 * 定义费用、网络或运行策略，集中维护相关边界值。
 */
public final class LitecoinFeePolicy {
    public static final long DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE = 2L;
    public static final long MIN_FEE_RATE_LITOSHI_PER_VBYTE = 1L;
    public static final long MAX_FEE_RATE_LITOSHI_PER_VBYTE = 100L;
    public static final long DUST_THRESHOLD_LITOSHI = LitecoinNetworkParameters.TESTNET_DUST_THRESHOLD_LITOSHI;

    private LitecoinFeePolicy() {
    }

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
