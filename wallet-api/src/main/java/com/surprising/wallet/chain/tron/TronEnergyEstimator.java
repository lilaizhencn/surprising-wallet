package com.surprising.wallet.chain.tron;

import java.math.BigDecimal;

/**
 * 负责 TRON 链地址、扫描、资源费用或交易处理。
 */
public class TronEnergyEstimator {
    /**
     * 定义 {@code DEFAULT_TRX_TRANSFER_BANDWIDTH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long DEFAULT_TRX_TRANSFER_BANDWIDTH = 268L;
    /**
     * 定义 {@code DEFAULT_TRC20_ENERGY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long DEFAULT_TRC20_ENERGY = 95_000L;
    /**
     * 计算或估算 {@code estimateBandwidth} 对应的金额、费用或资源消耗。
     */
    public long estimateBandwidth(boolean tokenTransfer) {
        return tokenTransfer ? DEFAULT_TRC20_ENERGY : DEFAULT_TRX_TRANSFER_BANDWIDTH;
    }
    /**
     * 计算或估算 {@code estimateFeeTrx} 对应的金额、费用或资源消耗。
     */
    public BigDecimal estimateFeeTrx(long energyPriceSun, boolean tokenTransfer) {
        long resource = estimateBandwidth(tokenTransfer);
        return BigDecimal.valueOf(resource * energyPriceSun).movePointLeft(6);
    }
}
