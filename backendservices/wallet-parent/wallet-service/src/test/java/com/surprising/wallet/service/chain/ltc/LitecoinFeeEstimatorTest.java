package com.surprising.wallet.service.chain.ltc;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitecoinFeeEstimatorTest {
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
