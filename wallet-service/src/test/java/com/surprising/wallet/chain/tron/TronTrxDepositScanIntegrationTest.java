package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

class TronTrxDepositScanIntegrationTest {
    @Test
    void liveFlow_shouldHaveCreditedTrxDepositOnce() throws Exception {
        Map<String, String> report = TronLiveFlowTestSupport.reportOrSkip();
        var jdbc = TronLiveFlowTestSupport.jdbcTemplate();
        TronLiveFlowTestSupport.assertCreditedDeposit(jdbc, report.get("trxDepositTxid"),
                report.get("userA"), "TRX", new BigDecimal("5"));
        TronLiveFlowTestSupport.assertConfirmedCollection(jdbc, report.get("trxCollectionTxid"));
        TronLiveFlowTestSupport.assertCreditedDeposit(jdbc, report.get("trxCollectionTxid"),
                report.get("hot"), "TRX", new BigDecimal("1"));
    }
}
