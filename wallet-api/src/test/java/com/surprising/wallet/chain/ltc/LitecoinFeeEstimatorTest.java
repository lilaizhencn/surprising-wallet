package com.surprising.wallet.chain.ltc;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.chain.model.TransferQuote;
import com.surprising.wallet.chain.model.TransferRequest;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code LitecoinFeeEstimatorTest} 覆盖的业务流程、边界条件和异常行为。
 */
class LitecoinFeeEstimatorTest {
    /**
     * 验证 {@code quoteShouldUseLitecoinFeePolicy} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void quoteShouldUseLitecoinFeePolicy() {
        LitecoinChainAdapter adapter = new LitecoinChainAdapter(null);
        TransferQuote quote = adapter.quoteNativeTransfer(new TransferRequest(
                ChainType.LTC, "LTC", "from", "to", new BigDecimal("0.1"), 1, null, null));

        long expected = P2wshFeeCalculator.calculateFeeSat(1, 2,
                LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE);
        assertEquals(ChainType.LTC, quote.chainType());
        assertEquals(expected, quote.maxFeePerGas());
        assertTrue(quote.supported());
    }

    /**
     * 验证 {@code highFeeRateShouldBeClamped} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void highFeeRateShouldBeClamped() {
        LitecoinChainAdapter adapter = new LitecoinChainAdapter(null);
        TransferQuote quote = adapter.quoteNativeTransfer(new TransferRequest(
                ChainType.LTC, "LTC", "from", "to", new BigDecimal("0.1"), 1, 10_000L, null));

        long expected = P2wshFeeCalculator.calculateFeeSat(1, 2,
                LitecoinFeePolicy.MAX_FEE_RATE_LITOSHI_PER_VBYTE);
        assertEquals(expected, quote.maxFeePerGas());
    }
}
