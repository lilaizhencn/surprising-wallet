package com.surprising.wallet.chain.tron;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@code Trc20CollectionIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class Trc20CollectionIntegrationTest {
    /**
     * 验证 {@code liveFlow_shouldHaveConfirmedTrc20CollectionAndCreditedHotWallet} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void liveFlow_shouldHaveConfirmedTrc20CollectionAndCreditedHotWallet() throws Exception {
        Map<String, String> report = TronLiveFlowTestSupport.reportOrSkip();
        var jdbc = TronLiveFlowTestSupport.jdbcTemplate();
        TronLiveFlowTestSupport.assertConfirmedCollection(jdbc, report.get("usdtCollectionTxid"));
        TronLiveFlowTestSupport.assertCreditedDeposit(jdbc, report.get("usdtCollectionTxid"),
                report.get("hot"), "USDT", new BigDecimal("30"));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TronLiveFlowTestSupport.ledger(jdbc, "USDT", report.get("userB"))));
        assertEquals(0, new BigDecimal("30").compareTo(
                TronLiveFlowTestSupport.ledger(jdbc, "USDT", report.get("hot"))));
        TronLiveFlowTestSupport.assertConfirmedCollection(jdbc, report.get("usdcCollectionTxid"));
        TronLiveFlowTestSupport.assertCreditedDeposit(jdbc, report.get("usdcCollectionTxid"),
                report.get("hot"), "USDC", new BigDecimal("30"));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TronLiveFlowTestSupport.ledger(jdbc, "USDC", report.get("userD"))));
        assertEquals(0, new BigDecimal("30").compareTo(
                TronLiveFlowTestSupport.ledger(jdbc, "USDC", report.get("hot"))));
        TronLiveFlowTestSupport.assertNoLockedOrNegativeLedger(jdbc, report.get("userB"), report.get("userD"),
                report.get("hot"));
    }
}
