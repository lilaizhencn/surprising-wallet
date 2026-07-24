package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

class Trc20DepositScanIntegrationTest {
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
