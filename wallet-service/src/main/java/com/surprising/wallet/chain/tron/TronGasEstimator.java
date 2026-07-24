package com.surprising.wallet.chain.tron;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates TRX gas top-up decisions for TRC20 signing and collection.
 * The result is bounded by maxGasTopup to prevent runaway gas funding loops.
 */
public class TronGasEstimator {
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
