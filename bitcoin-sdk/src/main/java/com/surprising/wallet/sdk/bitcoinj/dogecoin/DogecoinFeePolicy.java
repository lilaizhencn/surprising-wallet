package com.surprising.wallet.sdk.bitcoinj.dogecoin;

/**
 * Dogecoin Core fee recommendation expressed in koinu.
 */
public final class DogecoinFeePolicy {
    public static final long KOINU_PER_DOGE = 100_000_000L;
    public static final long RECOMMENDED_FEE_KOINU_PER_KB = 1_000_000L;
    public static final long DEFAULT_FEE_RATE_KOINU_PER_BYTE = 1_000L;
    public static final long MIN_RELAY_FEE_RATE_KOINU_PER_BYTE = 100L;
    public static final long MAX_FEE_RATE_KOINU_PER_BYTE = 100_000L;
    public static final long HARD_DUST_THRESHOLD_KOINU = 100_000L;
    public static final long RECOMMENDED_DUST_THRESHOLD_KOINU = 1_000_000L;

    private DogecoinFeePolicy() {
    }

    public static long clampFeeRate(long koinuPerByte) {
        return Math.max(MIN_RELAY_FEE_RATE_KOINU_PER_BYTE,
                Math.min(MAX_FEE_RATE_KOINU_PER_BYTE, koinuPerByte));
    }

    public static long feeForBytes(long bytes, long koinuPerByte) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("transaction bytes must be positive");
        }
        return Math.multiplyExact(bytes, clampFeeRate(koinuPerByte));
    }
}
