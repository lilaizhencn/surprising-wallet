package com.surprising.wallet.service.chain.ltc;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import com.surprising.wallet.service.chain.utxo.BitcoinLikeChainRuntime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class LitecoinChainAdapter implements BlockchainAdapter {
    private final BitcoinLikeChainRuntime runtime;

    public LitecoinChainAdapter(BitcoinLikeChainRuntime runtime) {
        this.runtime = runtime;
    }

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

    @Override
    public Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        return runtime.generateDepositAddress(ChainType.LTC, userId, biz);
    }

    @Override
    public boolean checkAddress(ChainType chainType, String address) {
        return runtime.checkAddress(ChainType.LTC, address);
    }

    @Override
    public long depositConfirmationThreshold(ChainType chainType) {
        return runtime.depositConfirmationThreshold(ChainType.LTC);
    }

    @Override
    public long dustThresholdAtomic(ChainType chainType) {
        return runtime.dustThresholdAtomic(ChainType.LTC);
    }

    @Override
    public long bestHeight(ChainType chainType) {
        return runtime.bestHeight(ChainType.LTC);
    }

    @Override
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        return runtime.findRelatedTransactions(ChainType.LTC, height);
    }

    @Override
    public void updateTransactionConfirmations(ChainType chainType) {
        runtime.updateTransactionConfirmations(ChainType.LTC);
    }

    @Override
    public void updateTotalBalance(ChainType chainType) {
        runtime.updateTotalBalance(ChainType.LTC);
    }

    @Override
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        return runtime.broadcastSignedTransaction(ChainType.LTC, transaction);
    }
}
