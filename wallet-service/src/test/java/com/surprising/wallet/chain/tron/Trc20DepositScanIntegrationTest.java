package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 验证 {@code Trc20DepositScanIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class Trc20DepositScanIntegrationTest {
    /**
     * 验证 {@code liveFlow_shouldHaveCreditedTrc20DepositsOnce} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void liveFlow_shouldHaveCreditedTrc20DepositsOnce() throws Exception {
        Map<String, String> report = TronLiveFlowTestSupport.reportOrSkip();
        var jdbc = TronLiveFlowTestSupport.jdbcTemplate();
        TronLiveFlowTestSupport.assertCreditedDeposit(jdbc, report.get("usdtDepositBTxid"),
                report.get("userB"), "USDT", new BigDecimal("30"));
        TronLiveFlowTestSupport.assertCreditedDeposit(jdbc, report.get("usdtDepositCTxid"),
                report.get("userC"), "USDT", new BigDecimal("20"));
        TronLiveFlowTestSupport.assertCreditedDeposit(jdbc, report.get("usdcDepositBTxid"),
                report.get("userD"), "USDC", new BigDecimal("30"));
        TronLiveFlowTestSupport.assertCreditedDeposit(jdbc, report.get("usdcDepositCTxid"),
                report.get("userE"), "USDC", new BigDecimal("20"));
    }
}
