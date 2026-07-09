package com.surprising.wallet.service.chain.tron;

import java.math.BigDecimal;

public record TronGasPolicy(BigDecimal minGasTopup,
                            BigDecimal maxGasTopup,
                            BigDecimal targetGasBalance,
                            long trc20FeeLimitSun,
                            BigDecimal reserveSafetyMultiplier) {
    public static TronGasPolicy nileDefault() {
        return new TronGasPolicy(new BigDecimal("1"), new BigDecimal("30"), new BigDecimal("10"),
                30_000_000L, new BigDecimal("1.20"));
    }
}
