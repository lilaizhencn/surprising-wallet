package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 验证 {@code TronTrxDepositScanIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TronTrxDepositScanIntegrationTest {
    /**
     * 验证 {@code liveFlow_shouldHaveCreditedTrxDepositOnce} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
