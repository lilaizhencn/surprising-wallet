package com.surprising.wallet.service.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TronGasTopupIntegrationTest {
    @Test
    void liveFlow_shouldHaveConfirmedGasTopupsAndWaitingGasPolicy() throws Exception {
        Map<String, String> report = TronLiveFlowTestSupport.reportOrSkip();
        var jdbc = TronLiveFlowTestSupport.jdbcTemplate();
        TronLiveFlowTestSupport.assertConfirmedGasTopup(jdbc, report.get("usdtGasTopupBTxid"));
        TronLiveFlowTestSupport.assertConfirmedGasTopup(jdbc, report.get("usdtGasTopupCTxid"));
        TronLiveFlowTestSupport.assertConfirmedGasTopup(jdbc, report.get("usdcGasTopupBTxid"));
        TronLiveFlowTestSupport.assertConfirmedGasTopup(jdbc, report.get("usdcGasTopupCTxid"));
        assertTrue(new BigDecimal(report.get("usdtEstimatedEnergy")).signum() > 0);
        assertTrue(new BigDecimal(report.get("usdtEstimatedFeeTrx")).signum() > 0);
        assertTrue(new BigDecimal(report.get("usdcEstimatedEnergy")).signum() > 0);
        assertTrue(new BigDecimal(report.get("usdcEstimatedFeeTrx")).signum() > 0);
        TronLiveFlowTestSupport.assertNoLockedOrNegativeLedger(jdbc, report.get("userB"), report.get("userC"),
                report.get("userD"), report.get("userE"));
    }
}
