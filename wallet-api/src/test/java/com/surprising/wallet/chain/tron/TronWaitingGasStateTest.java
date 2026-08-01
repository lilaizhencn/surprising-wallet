package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TronWaitingGasStateTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TronWaitingGasStateTest {
    /**
     * 验证 {@code insufficientGas_shouldCreateDeterministicTopupTask} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void insufficientGas_shouldCreateDeterministicTopupTask() {
        TronWaitingGasStateService service = new TronWaitingGasStateService();
        var decision = service.evaluate("TRON_NILE", "ORDER-1", "TAddress",
                new BigDecimal("0.1"), new BigDecimal("8"), TronGasPolicy.nileDefault());
        assertTrue(decision.waitingGas());
        assertTrue(decision.gasTaskNo().contains("ORDER-1"));
        assertTrue(decision.topupAmount().compareTo(BigDecimal.ZERO) > 0);
    }

    /**
     * 验证 {@code sufficientGas_shouldNotCreateTopupTask} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void sufficientGas_shouldNotCreateTopupTask() {
        TronWaitingGasStateService service = new TronWaitingGasStateService();
        var decision = service.evaluate("TRON_NILE", "ORDER-2", "TAddress",
                new BigDecimal("15"), new BigDecimal("8"), TronGasPolicy.nileDefault());
        assertFalse(decision.waitingGas());
    }
}
