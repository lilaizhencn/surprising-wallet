package com.surprising.wallet.service.chain.tron;

import java.math.BigDecimal;

/**
 * Simple deterministic TRON fee model.
 */
public class TronEnergyEstimator {
    public static final long DEFAULT_TRX_TRANSFER_BANDWIDTH = 268L;
    public static final long DEFAULT_TRC20_ENERGY = 95_000L;
    public long estimateBandwidth(boolean tokenTransfer) {
        return tokenTransfer ? DEFAULT_TRC20_ENERGY : DEFAULT_TRX_TRANSFER_BANDWIDTH;
    }
    public BigDecimal estimateFeeTrx(long energyPriceSun, boolean tokenTransfer) {
        long resource = estimateBandwidth(tokenTransfer);
        return BigDecimal.valueOf(resource * energyPriceSun).movePointLeft(6);
    }
}
