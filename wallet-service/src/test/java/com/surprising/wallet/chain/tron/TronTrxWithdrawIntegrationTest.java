package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * 验证 {@code TronTrxWithdrawIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TronTrxWithdrawIntegrationTest {
    /**
     * 验证 {@code liveFlow_shouldHaveConfirmedTwoTrxWithdrawals} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void liveFlow_shouldHaveConfirmedTwoTrxWithdrawals() throws Exception {
        Map<String, String> report = TronLiveFlowTestSupport.reportOrSkip();
        var jdbc = TronLiveFlowTestSupport.jdbcTemplate();
        TronLiveFlowTestSupport.assertConfirmedWithdrawal(jdbc, report.get("trxWithdraw1Txid"), "TRX");
        TronLiveFlowTestSupport.assertConfirmedWithdrawal(jdbc, report.get("trxWithdraw2Txid"), "TRX");
        TronLiveFlowTestSupport.assertNoLockedOrNegativeLedger(jdbc, report.get("userA"));
    }
}
