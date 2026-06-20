package com.surprising.wallet.service.chain.btc;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class BtcChainAdapter implements BlockchainAdapter {
    @Override
    public ChainType chainType() {
        return ChainType.BTC;
    }

    @Override
    public String family() {
        return "bitcoin";
    }

    @Override
    public String describe() {
        return "Isolated BTC UTXO adapter with SegWit fee estimation and multisig-safe planning.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null ? 10L : request.feeRateSatPerVByte();
        long amountSat = request.amount().movePointRight(8).setScale(0, RoundingMode.DOWN).longValueExact();
        long feeSat = P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate);
        long feeBtcSat = feeSat;
        BigDecimal fee = BigDecimal.valueOf(feeBtcSat).movePointLeft(8);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeBtcSat, 0L, null, true, "btc utxo fee estimate");
    }

}
