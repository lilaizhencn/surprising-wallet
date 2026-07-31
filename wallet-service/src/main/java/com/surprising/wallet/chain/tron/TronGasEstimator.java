package com.surprising.wallet.chain.tron;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 负责 TRON 链地址、扫描、资源费用或交易处理。
 */
public class TronGasEstimator {
    /**
     * 执行 {@code decideTopup} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public GasDecision decideTopup(BigDecimal currentTrxBalance, BigDecimal estimatedRequiredTrx, TronGasPolicy policy) {        if (currentTrxBalance.compareTo(estimatedRequiredTrx) >= 0
                && currentTrxBalance.compareTo(policy.targetGasBalance()) >= 0) {
            return new GasDecision(false, BigDecimal.ZERO, "sufficient gas");
        }
        BigDecimal target = estimatedRequiredTrx.multiply(policy.reserveSafetyMultiplier())
                .max(policy.targetGasBalance());
        BigDecimal topup = target.subtract(currentTrxBalance).max(policy.minGasTopup())
                .setScale(6, RoundingMode.UP);
        if (topup.compareTo(policy.maxGasTopup()) > 0) {
            topup = policy.maxGasTopup();
        }
        return new GasDecision(topup.signum() > 0, topup, "top up TRX for TRC20 energy/bandwidth");
    }
    /**
     * 计算或估算 {@code estimateTrc20FeeTrx} 对应的金额、费用或资源消耗。
     */
    public BigDecimal estimateTrc20FeeTrx(long energyUsed, long energyPriceSun, TronGasPolicy policy) {
        BigDecimal energyFee = BigDecimal.valueOf(energyUsed)
                .multiply(BigDecimal.valueOf(energyPriceSun))
                .movePointLeft(6);
        BigDecimal feeLimit = BigDecimal.valueOf(policy.trc20FeeLimitSun()).movePointLeft(6);
        return energyFee.min(feeLimit);
    }
    public record GasDecision(boolean waitingGas, BigDecimal topupAmount, String reason) {
    }
}
