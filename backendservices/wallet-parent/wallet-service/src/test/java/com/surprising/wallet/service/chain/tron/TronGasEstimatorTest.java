package com.surprising.wallet.service.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TronGasEstimatorTest {
    @Test
    void trxSufficient_shouldNotTopup() {
        TronGasEstimator estimator = new TronGasEstimator();
        TronGasEstimator.GasDecision decision = estimator.decideTopup(new BigDecimal("11"), new BigDecimal("2"),
                TronGasPolicy.nileDefault());
        assertFalse(decision.waitingGas());
        assertEquals(BigDecimal.ZERO, decision.topupAmount());
    }

    @Test
    void trxInsufficient_shouldEnterWaitingGasWithBoundedTopup() {
        TronGasEstimator estimator = new TronGasEstimator();
        TronGasPolicy policy = new TronGasPolicy(new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("10"),
                30_000_000L, new BigDecimal("1.20"));
        TronGasEstimator.GasDecision decision = estimator.decideTopup(new BigDecimal("0.1"), new BigDecimal("9"), policy);
        assertTrue(decision.waitingGas());
        assertEquals(new BigDecimal("5"), decision.topupAmount());
    }

    @Test
    void trc20Fee_shouldRespectFeeLimit() {
        TronGasEstimator estimator = new TronGasEstimator();
        BigDecimal fee = estimator.estimateTrc20FeeTrx(200_000L, 420L, TronGasPolicy.nileDefault());
        assertEquals(new BigDecimal("30.000000"), fee.setScale(6));
    }
}
