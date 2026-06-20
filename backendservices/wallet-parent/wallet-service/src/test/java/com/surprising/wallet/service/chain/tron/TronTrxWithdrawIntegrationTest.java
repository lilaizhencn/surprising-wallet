package com.surprising.wallet.service.chain.tron;

import org.junit.jupiter.api.Test;

import java.util.Map;

class TronTrxWithdrawIntegrationTest {
    @Test
    void liveFlow_shouldHaveConfirmedTwoTrxWithdrawals() throws Exception {
        Map<String, String> report = TronLiveFlowTestSupport.reportOrSkip();
        var jdbc = TronLiveFlowTestSupport.jdbcTemplate();
        TronLiveFlowTestSupport.assertConfirmedWithdrawal(jdbc, report.get("trxWithdraw1Txid"), "TRX");
        TronLiveFlowTestSupport.assertConfirmedWithdrawal(jdbc, report.get("trxWithdraw2Txid"), "TRX");
        TronLiveFlowTestSupport.assertNoLockedOrNegativeLedger(jdbc, report.get("userA"));
    }
}
