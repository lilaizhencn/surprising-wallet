package com.surprising.wallet.sdk.bitcoinj.litecoin;

/**
 * Litecoin-specific fee and dust defaults. Values are intentionally separated
 * from BTC so fee tuning can evolve per chain without changing BTC behavior.
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
