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
        TronLiveFlowTestSupport.assertConfirmedGasTopup(jdbc, report.get("gasTopupBTxid"));
        TronLiveFlowTestSupport.assertConfirmedGasTopup(jdbc, report.get("gasTopupCTxid"));
        assertTrue(new BigDecimal(report.get("estimatedEnergy")).signum() > 0);
        assertTrue(new BigDecimal(report.get("estimatedFeeTrx")).signum() > 0);
        TronLiveFlowTestSupport.assertNoLockedOrNegativeLedger(jdbc, report.get("userB"), report.get("userC"));
    }
}
