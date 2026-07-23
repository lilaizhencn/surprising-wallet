package com.surprising.wallet.service.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Trc20WithdrawIntegrationTest {
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
