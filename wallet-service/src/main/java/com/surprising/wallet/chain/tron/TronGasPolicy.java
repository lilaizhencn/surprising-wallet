package com.surprising.wallet.chain.tron;

import java.math.BigDecimal;

/**
 * TRON Gas 策略配置，定义 Gas 补充的上下限和目标余额。
 *
 * @param minGasTopup           单次补 Gas 下限（TRX）
 * @param maxGasTopup           单次补 Gas 上限（TRX）
 * @param targetGasBalance      Gas 账户目标余额（TRX）
 * @param trc20FeeLimitSun      TRC20 交易的单次手续费上限（sun）
 * @param reserveSafetyMultiplier 余额安全系数（如 1.20 表示多保留 20%）
 */
public record TronGasPolicy(BigDecimal minGasTopup,
                            BigDecimal maxGasTopup,
                            BigDecimal targetGasBalance,
                            long trc20FeeLimitSun,
                            BigDecimal reserveSafetyMultiplier) {    public static TronGasPolicy nileDefault() {
        return new TronGasPolicy(new BigDecimal("1"), new BigDecimal("30"), new BigDecimal("10"),
                30_000_000L, new BigDecimal("1.20"));
    }
}
