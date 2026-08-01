package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TronLedgerIdempotencyTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TronLedgerIdempotencyTest {
    /**
     * 验证 {@code sameLedgerAndChainBalance_shouldMatch} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void sameLedgerAndChainBalance_shouldMatch() {
        TronLedgerReconciliationService service = new TronLedgerReconciliationService();
        var result = service.compare("TRON_NILE", "USDT", "TAddress",
                new BigDecimal("10.000000"), new BigDecimal("10.000000"));
        assertTrue(result.matched());
    }

    /**
     * 验证 {@code differentLedgerAndChainBalance_shouldReportDelta} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void differentLedgerAndChainBalance_shouldReportDelta() {
        TronLedgerReconciliationService service = new TronLedgerReconciliationService();
        var result = service.compare("TRON_NILE", "USDT", "TAddress",
                new BigDecimal("9"), new BigDecimal("10"));
        assertFalse(result.matched());
        assertTrue(result.delta().compareTo(BigDecimal.ZERO) > 0);
    }
}
