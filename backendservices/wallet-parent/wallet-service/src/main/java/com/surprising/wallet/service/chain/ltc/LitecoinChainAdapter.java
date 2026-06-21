package com.surprising.wallet.service.chain.ltc;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class LitecoinChainAdapter implements BlockchainAdapter {
    @Override
    public ChainType chainType() {
        return ChainType.LTC;
    }

    @Override
    public String family() {
        return "litecoin";
    }

    @Override
    public String describe() {
        return "Litecoin BTC-like UTXO adapter with isolated network params, fee policy, and P2WSH signing.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null
                ? LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE
                : LitecoinFeePolicy.clampFeeRate(request.feeRateSatPerVByte());
        long feeLitoshi = P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate);
        BigDecimal fee = BigDecimal.valueOf(feeLitoshi).movePointLeft(8).setScale(8, RoundingMode.DOWN);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeLitoshi, 0L, null, true, "ltc p2wsh fee estimate");
    }
}
