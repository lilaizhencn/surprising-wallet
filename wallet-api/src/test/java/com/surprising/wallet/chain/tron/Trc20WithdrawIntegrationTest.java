package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@code Trc20WithdrawIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class Trc20WithdrawIntegrationTest {
    /**
     * 验证 {@code liveFlow_shouldHaveConfirmedTrc20WithdrawAndSettledLedger} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void liveFlow_shouldHaveConfirmedTrc20WithdrawAndSettledLedger() throws Exception {
        Map<String, String> report = TronLiveFlowTestSupport.reportOrSkip();
        var jdbc = TronLiveFlowTestSupport.jdbcTemplate();
        TronLiveFlowTestSupport.assertConfirmedWithdrawal(jdbc, report.get("usdtWithdrawTxid"), "USDT");
        assertEquals(0, new BigDecimal("15").compareTo(
                TronLiveFlowTestSupport.ledger(jdbc, "USDT", report.get("userC"))));
        TronLiveFlowTestSupport.assertConfirmedWithdrawal(jdbc, report.get("usdcWithdrawTxid"), "USDC");
        assertEquals(0, new BigDecimal("15").compareTo(
                TronLiveFlowTestSupport.ledger(jdbc, "USDC", report.get("userE"))));
        TronLiveFlowTestSupport.assertNoLockedOrNegativeLedger(jdbc, report.get("userC"), report.get("userE"));
    }
}
